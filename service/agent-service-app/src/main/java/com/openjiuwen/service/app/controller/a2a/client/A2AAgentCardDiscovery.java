/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.A2AProperties.RemoteAgentProperties;

import jakarta.annotation.PreDestroy;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fetches AgentCards from configured remote A2A servers at startup. Successful
 * fetches are cached permanently; failures
 * are retried every 30s.
 *
 * @since 0.1.0
 */
public class A2AAgentCardDiscovery {
    private static final Logger log = LoggerFactory.getLogger(A2AAgentCardDiscovery.class);

    private static final long RETRY_INTERVAL_SECONDS = 30L;

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final A2AProperties properties;

    private final A2ARemoteAgentCardRegistry registry;

    private final RestClient restClient;

    private final ScheduledExecutorService retryExecutor;

    private final Map<String, ScheduledFuture<?>> retryFutures = new ConcurrentHashMap<>();

    /**
     * Constructs the agent card discovery service.
     *
     * @param properties the A2A configuration properties
     * @param registry the remote agent card registry
     */
    public A2AAgentCardDiscovery(A2AProperties properties, A2ARemoteAgentCardRegistry registry) {
        this.properties = properties;
        this.registry = registry;
        this.restClient = RestClient.create();
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "a2a-discovery-retry");
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
        if (properties.getRemoteAgents().isEmpty()) {
            return;
        }
        validateRemoteAgents();
        log.info("Discovering {} remote A2A agent(s)", properties.getRemoteAgents().size());
        for (var remote : properties.getRemoteAgents()) {
            tryDiscover(remote);
        }
    }

    private void validateRemoteAgents() {
        for (int index = 0; index < properties.getRemoteAgents().size(); index++) {
            RemoteAgentProperties remote = properties.getRemoteAgents().get(index);
            if (remote.getUrl() == null || remote.getUrl().isBlank()) {
                throw new IllegalStateException("Remote agent '" + remote.getName()
                        + "' has no URL configured. Set openjiuwen.service.a2a.remote-agents[" + index
                        + "].url or remove this agent.");
            }
        }
    }

    private void tryDiscover(RemoteAgentProperties remote) {
        try {
            discoverAndRegister(remote);
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("Failed to discover {}, retry every {}s: {}", remote.getName(), RETRY_INTERVAL_SECONDS,
                    e.getMessage());
            ScheduledFuture<?> future = retryExecutor.scheduleWithFixedDelay(() -> {
                try {
                    discoverAndRegister(remote);
                    log.info("Retry successful, discovered remote agent '{}'", remote.getName());
                    cancelRetry(remote.getName());
                } catch (org.springframework.web.client.RestClientException ex) {
                    log.warn("Retry {} failed, will retry in {}s: {}", remote.getName(), RETRY_INTERVAL_SECONDS,
                            ex.getMessage());
                }
            }, RETRY_INTERVAL_SECONDS, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
            retryFutures.put(remote.getName(), future);
        }
    }

    private void cancelRetry(String agentName) {
        ScheduledFuture<?> future = retryFutures.remove(agentName);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void discoverAndRegister(RemoteAgentProperties remote) {
        AgentCard card = fetchCard(remote.getUrl());
        registry.register(remote.getName(), card, remote.getTimeoutSeconds());
        log.info("Discovered remote agent '{}'", remote.getName());
    }

    AgentCard fetchCard(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        String cardUrl = baseUrl.replaceAll("/$", "") + "/.well-known/agent-card.json";
        return restClient.get().uri(cardUrl).accept(MediaType.APPLICATION_JSON).retrieve().body(AgentCard.class);
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
