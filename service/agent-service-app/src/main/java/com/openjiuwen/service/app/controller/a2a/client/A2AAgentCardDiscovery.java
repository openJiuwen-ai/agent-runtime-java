/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.A2AProperties.RemoteAgentProperties;
import com.openjiuwen.service.app.hosting.HostedRemoteAgentCatalogs;
import com.openjiuwen.service.spec.paths.A2AServicePaths;

import jakarta.annotation.PreDestroy;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Fetches AgentCards from configured remote A2A servers at startup and resolves
 * remote agent URLs at runtime.
 *
 * <p>This bean implements {@link RemoteAgentCardResolver} as the baseline
 * resolver: {@link #resolveJsonRpcUrl} reads the cached card's first interface
 * URL from {@link A2ARemoteAgentCardRegistry}, and {@link #resolveCardUrl}
 * derives the agent-card fetch URL by stripping the JSON-RPC endpoint's last
 * path segment and appending {@link A2AServicePaths#WELL_KNOWN_AGENT_CARD},
 * preserving any path prefix. Deployments may override with an
 * {@code A2AGatewayCardResolver} for cross-origin cards.
 *
 * <p>Successful startup fetches are cached permanently; failures are retried
 * every 30s.
 *
 * @since 0.1.0
 */
public class A2AAgentCardDiscovery implements RemoteAgentCardResolver {
    private static final Logger log = LoggerFactory.getLogger(A2AAgentCardDiscovery.class);

    private static final String REMOTE_AGENTS_PROPERTY = "openjiuwen.service.a2a.remote-agents";

    private static final long RETRY_INTERVAL_SECONDS = 30L;

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final A2AProperties properties;

    private final A2ARemoteAgentCardRegistry registry;

    private final HostedRemoteAgentCatalogs hostedCatalogs;

    private final RestClient restClient;

    private final ScheduledExecutorService retryExecutor;

    private final Map<DiscoveryKey, ScheduledFuture<?>> retryFutures = new ConcurrentHashMap<>();

    /**
     * Constructs the agent card discovery service.
     *
     * @param properties the A2A configuration properties
     * @param registry the remote agent card registry
     */
    public A2AAgentCardDiscovery(A2AProperties properties, A2ARemoteAgentCardRegistry registry) {
        this(properties, registry, null);
    }

    /**
     * Creates discovery with optional instance-local directories and one shared retry scheduler.
     *
     * @param properties global configuration
     * @param registry global directory
     * @param hostedCatalogs hosted directories, or null for legacy mode
     */
    public A2AAgentCardDiscovery(A2AProperties properties, A2ARemoteAgentCardRegistry registry,
            HostedRemoteAgentCatalogs hostedCatalogs) {
        this.properties = properties;
        this.registry = registry;
        this.hostedCatalogs = hostedCatalogs;
        this.restClient = RestClient.create();
        ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();
        this.retryExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread t = defaultThreadFactory.newThread(runnable);
            t.setName("a2a-discovery-retry");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(
                    (thread, ex) -> log.error("Uncaught exception in discovery thread {}", thread.getName(), ex));
            return t;
        });
    }

    /**
     * Discovers all configured remote A2A agents on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void discoverAll() {
        validateRemoteAgents(properties.getRemoteAgents(), REMOTE_AGENTS_PROPERTY);
        if (hostedCatalogs != null) {
            hostedCatalogs.localConfigurations().forEach((agentId, remotes) -> validateRemoteAgents(remotes,
                    "openjiuwen.service.a2a.agents." + agentId + ".remote-agents"));
        }
        log.info("Discovering {} remote A2A agent(s)", properties.getRemoteAgents().size());
        for (var remote : properties.getRemoteAgents()) {
            tryDiscover(remote, registry, null);
        }
        if (hostedCatalogs != null) {
            hostedCatalogs.localConfigurations().forEach((agentId, remotes) -> remotes.forEach(remote ->
                    tryDiscover(remote, hostedCatalogs.catalog(agentId), agentId)));
        }
    }

    private static void validateRemoteAgents(List<RemoteAgentProperties> remotes, String prefix) {
        for (int index = 0; index < remotes.size(); index++) {
            RemoteAgentProperties remote = remotes.get(index);
            String propertyPrefix = prefix + "[" + index + "]";
            validateRequiredProperty(remote.getName(), propertyPrefix + ".name");
            validateRequiredProperty(remote.getUrl(), propertyPrefix + ".url");
        }
    }

    private static void validateRequiredProperty(String value, String propertyPath) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Invalid A2A remote agent configuration: " + propertyPath + " must not be null or blank");
        }
    }

    private void tryDiscover(RemoteAgentProperties remote, A2ARemoteAgentCardRegistry target, String agentId) {
        var key = new DiscoveryKey(agentId, remote.getName());
        try {
            discoverAndRegister(remote, target);
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("Failed to discover {}, retry every {}s: {}", remote.getName(), RETRY_INTERVAL_SECONDS,
                    e.getMessage());
            ScheduledFuture<?> future = retryExecutor.scheduleWithFixedDelay(() -> {
                try {
                    discoverAndRegister(remote, target);
                    log.info("Retry successful, discovered remote agent '{}'", remote.getName());
                    cancelRetry(key);
                } catch (org.springframework.web.client.RestClientException ex) {
                    log.warn("Retry {} failed, will retry in {}s: {}", remote.getName(), RETRY_INTERVAL_SECONDS,
                            ex.getMessage());
                }
            }, RETRY_INTERVAL_SECONDS, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
            retryFutures.put(key, future);
        }
    }

    private void cancelRetry(DiscoveryKey key) {
        ScheduledFuture<?> future = retryFutures.remove(key);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void discoverAndRegister(RemoteAgentProperties remote, A2ARemoteAgentCardRegistry target) {
        AgentCard card = fetchCardInternal(remote.getUrl());
        target.register(remote.getName(), card, remote.getTimeoutSeconds(), remote.isStreaming(), remote.getTls());
        log.info("Discovered remote agent '{}'", remote.getName());
    }

    AgentCard fetchCardInternal(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        String cardUrl = baseUrl.replaceAll("/$", "") + "/.well-known/agent-card.json";
        return restClient.get().uri(cardUrl).accept(MediaType.APPLICATION_JSON).retrieve().body(AgentCard.class);
    }

    private record DiscoveryKey(String agentId, String remoteName) {
    }

    @Override
    public String resolveCardUrl(String agentId) {
        String jsonRpcUrl = resolveJsonRpcUrl(agentId);
        if (jsonRpcUrl == null || jsonRpcUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(jsonRpcUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return "";
            }
            StringBuilder base = new StringBuilder().append(scheme).append("://").append(host);
            int port = uri.getPort();
            if (port > 0) {
                base.append(':').append(port);
            }
            String path = uri.getRawPath();
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                while (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash > 0) {
                    base.append(path, 0, lastSlash);
                }
            }
            return base.append(A2AServicePaths.WELL_KNOWN_AGENT_CARD).toString();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    @Override
    public String resolveJsonRpcUrl(String agentId) {
        if (agentId == null) {
            return "";
        }
        return registry.resolveUrl(agentId);
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && registry.get(agentId).isPresent();
    }

    /**
     * Shuts down the retry executor gracefully, cancelling all pending retries and
     * waiting for any in-flight
     * task to complete before forcing termination.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down A2A discovery retry executor");
        retryFutures.values().forEach(f -> f.cancel(false));
        retryFutures.clear();
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
        }
    }
}
