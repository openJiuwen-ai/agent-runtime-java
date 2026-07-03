/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.core.sysop.config.ContainerScope;
import com.openjiuwen.service.adapters.common.external.ExternalAuditPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalCallPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalCircuitBreakerPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalRetryPolicy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * External service adapter properties for agent-core integrations.
 *
 * @since 2026-06-24
 */
@ConfigurationProperties(prefix = "openjiuwen.service.external")
public class AgentCoreExternalProperties {
    private McpPolicy mcp = new McpPolicy();

    private RemotePolicy remote = new RemotePolicy();

    private SandboxPolicy sandbox = new SandboxPolicy();

    public McpPolicy getMcp() {
        return mcp;
    }

    public void setMcp(McpPolicy mcp) {
        this.mcp = mcp != null ? mcp : new McpPolicy();
    }

    public RemotePolicy getRemote() {
        return remote;
    }

    public void setRemote(RemotePolicy remote) {
        this.remote = remote != null ? remote : new RemotePolicy();
    }

    public SandboxPolicy getSandbox() {
        return sandbox;
    }

    public void setSandbox(SandboxPolicy sandbox) {
        this.sandbox = sandbox != null ? sandbox : new SandboxPolicy();
    }

    /**
     * Validates external service adapter properties.
     */
    public void validate() {
        mcp.validate();
        remote.validateClients();
        sandbox.validate();
    }

    /**
     * Builds the MCP external call policy for a server config.
     *
     * @param config MCP server config
     * @return external call policy for the MCP server
     */
    public McpPolicy policyFor(McpServerConfig config) {
        McpPolicy policy = mcp.copyWithoutServers();
        mcp.findServer(config).map(McpServer::getTimeoutMs).ifPresent(policy::setTimeoutMs);
        return policy;
    }

    /**
     * Builds the remote-client external call policy for a client config.
     *
     * @param config remote client config
     * @return external call policy for the remote client
     */
    public RemotePolicy policyFor(RemoteClientConfig config) {
        RemotePolicy policy = remote.copyWithoutClients();
        remote.findClient(config).map(RemoteClientEndpoint::getTimeoutMs).ifPresent(policy::setTimeoutMs);
        return policy;
    }

    /**
     * Builds the sandbox external call policy for a server config.
     *
     * @param server configured sandbox server
     * @return external call policy for the sandbox server
     */
    public SandboxPolicy policyFor(Optional<SandboxServer> server) {
        SandboxPolicy policy = sandbox.copyWithoutServers();
        server.map(SandboxServer::getTimeoutMs).ifPresent(policy::setTimeoutMs);
        return policy;
    }

    private static void validateCallPolicy(String policyName, int timeoutMs, ExternalRetryPolicy retry,
            ExternalCircuitBreakerPolicy circuitBreaker) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException(policyName + " timeout-ms must be greater than zero");
        }
        if (retry.getMax() < 0) {
            throw new IllegalArgumentException(policyName + " retry.max must be greater than or equal to zero");
        }
        if (retry.getBackoffMs() < 0) {
            throw new IllegalArgumentException(policyName + " retry.backoff-ms must be greater than or equal to zero");
        }
        if (circuitBreaker.getFailureThreshold() <= 0) {
            throw new IllegalArgumentException(
                    policyName + " circuit-breaker.failure-threshold must be greater than zero");
        }
        if (circuitBreaker.getResetTimeoutMs() <= 0) {
            throw new IllegalArgumentException(
                    policyName + " circuit-breaker.reset-timeout-ms must be greater than zero");
        }
    }

    /**
     * MCP external call policy properties.
     */
    public static class McpPolicy implements ExternalCallPolicy {
        private List<McpServer> servers = new ArrayList<>();

        private int timeoutMs = 30000;

        private ExternalRetryPolicy retry = new ExternalRetryPolicy();

        private ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();

        private ExternalAuditPolicy audit = new ExternalAuditPolicy();

        private boolean shouldRetryToolCalls = false;

        public List<McpServer> getServers() {
            return servers;
        }

        public void setServers(List<McpServer> servers) {
            this.servers = servers != null ? servers : new ArrayList<>();
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("MCP timeout-ms must be greater than zero");
            }
            this.timeoutMs = timeoutMs;
        }

        public ExternalRetryPolicy getRetry() {
            return retry;
        }

        public void setRetry(ExternalRetryPolicy retry) {
            this.retry = retry != null ? retry : new ExternalRetryPolicy();
        }

        public ExternalCircuitBreakerPolicy getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(ExternalCircuitBreakerPolicy circuitBreaker) {
            this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new ExternalCircuitBreakerPolicy();
        }

        public ExternalAuditPolicy getAudit() {
            return audit;
        }

        public void setAudit(ExternalAuditPolicy audit) {
            this.audit = audit != null ? audit : new ExternalAuditPolicy();
        }

        public boolean isRetryToolCalls() {
            return shouldRetryToolCalls;
        }

        public void setRetryToolCalls(boolean shouldRetryToolCalls) {
            this.shouldRetryToolCalls = shouldRetryToolCalls;
        }

        /**
         * Validates configured MCP policy and servers.
         */
        public void validate() {
            validateCallPolicy("MCP", timeoutMs, retry, circuitBreaker);
            if (servers == null || servers.isEmpty()) {
                return;
            }
            for (McpServer server : servers) {
                validateServer(server);
            }
        }

        private Optional<McpServer> findServer(McpServerConfig config) {
            if (config == null) {
                return Optional.empty();
            }
            for (McpServer server : servers) {
                if (server == null) {
                    continue;
                }
                if (config.getServerId() != null && config.getServerId().equals(server.getServerId())) {
                    return Optional.of(server);
                }
                if (config.getServerName() != null && config.getServerName().equals(server.getServerName())) {
                    return Optional.of(server);
                }
            }
            return Optional.empty();
        }

        private void validateServer(McpServer server) {
            if (server == null) {
                throw new IllegalArgumentException("MCP server config must not be null");
            }
            if (server.getServerName() == null || server.getServerName().isBlank()) {
                throw new IllegalArgumentException("MCP server-name must not be blank");
            }
            if (server.getServerPath() == null || server.getServerPath().isBlank()) {
                throw new IllegalArgumentException("MCP server-path must not be blank");
            }
            if (server.getTimeoutMs() != null && server.getTimeoutMs() <= 0) {
                throw new IllegalArgumentException("MCP server timeout-ms must be greater than zero");
            }
        }

        private McpPolicy copyWithoutServers() {
            McpPolicy copy = new McpPolicy();
            copy.setServers(List.of());
            copy.setTimeoutMs(timeoutMs);
            copy.setRetry(retry.copy());
            copy.setCircuitBreaker(circuitBreaker.copy());
            copy.setAudit(audit.copy());
            copy.setRetryToolCalls(shouldRetryToolCalls);
            return copy;
        }
    }

    /**
     * MCP server-specific external call properties.
     */
    public static class McpServer {
        private String serverId;

        private String serverName;

        private String serverPath;

        private String clientType = "sse";

        private Map<String, Object> params = new LinkedHashMap<>();

        private Integer timeoutMs;

        private String tag;

        private Double expiryTimeMs;

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public String getServerPath() {
            return serverPath;
        }

        public void setServerPath(String serverPath) {
            this.serverPath = serverPath;
        }

        public String getClientType() {
            return clientType;
        }

        public void setClientType(String clientType) {
            this.clientType = clientType;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params != null ? params : new LinkedHashMap<>();
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public Double getExpiryTimeMs() {
            return expiryTimeMs;
        }

        public void setExpiryTimeMs(Double expiryTimeMs) {
            this.expiryTimeMs = expiryTimeMs;
        }
    }

    /**
     * Remote-client external call policy properties.
     */
    public static class RemotePolicy implements ExternalCallPolicy {
        private List<RemoteClientEndpoint> clients = new ArrayList<>();

        private int timeoutMs = 30000;

        private ExternalRetryPolicy retry = new ExternalRetryPolicy();

        private ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();

        private ExternalAuditPolicy audit = new ExternalAuditPolicy();

        private boolean shouldRetryInvoke = false;

        public List<RemoteClientEndpoint> getClients() {
            return clients;
        }

        public void setClients(List<RemoteClientEndpoint> clients) {
            this.clients = clients != null ? clients : new ArrayList<>();
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("Remote timeout-ms must be greater than zero");
            }
            this.timeoutMs = timeoutMs;
        }

        public ExternalRetryPolicy getRetry() {
            return retry;
        }

        public void setRetry(ExternalRetryPolicy retry) {
            this.retry = retry != null ? retry : new ExternalRetryPolicy();
        }

        public ExternalCircuitBreakerPolicy getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(ExternalCircuitBreakerPolicy circuitBreaker) {
            this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new ExternalCircuitBreakerPolicy();
        }

        public ExternalAuditPolicy getAudit() {
            return audit;
        }

        public void setAudit(ExternalAuditPolicy audit) {
            this.audit = audit != null ? audit : new ExternalAuditPolicy();
        }

        public boolean isRetryInvoke() {
            return shouldRetryInvoke;
        }

        public void setRetryInvoke(boolean shouldRetryInvoke) {
            this.shouldRetryInvoke = shouldRetryInvoke;
        }

        /**
         * Validates configured remote clients.
         */
        public void validateClients() {
            validateCallPolicy("Remote", timeoutMs, retry, circuitBreaker);
            if (clients == null || clients.isEmpty()) {
                return;
            }
            for (RemoteClientEndpoint client : clients) {
                validateClient(client);
            }
        }

        /**
         * Finds the configured remote client for the given client id.
         *
         * @param clientId remote client id
         * @return configured remote client, or empty when no client matches
         */
        public Optional<RemoteClientEndpoint> findClient(String clientId) {
            if (clients == null || clients.isEmpty()) {
                return Optional.empty();
            }
            if (clientId == null || clientId.isBlank()) {
                return Optional.ofNullable(clients.get(0));
            }
            for (RemoteClientEndpoint client : clients) {
                if (client != null && clientId.equals(client.getId())) {
                    return Optional.of(client);
                }
            }
            return Optional.empty();
        }

        private Optional<RemoteClientEndpoint> findClient(RemoteClientConfig config) {
            if (config == null || clients == null || clients.isEmpty()) {
                return Optional.empty();
            }
            for (RemoteClientEndpoint client : clients) {
                if (client == null) {
                    continue;
                }
                if (config.getId() != null && config.getId().equals(client.getId())) {
                    return Optional.of(client);
                }
                if (config.getName() != null && config.getName().equals(client.getName())) {
                    return Optional.of(client);
                }
            }
            return Optional.empty();
        }

        private void validateClient(RemoteClientEndpoint client) {
            if (client == null) {
                throw new IllegalArgumentException("Remote client config must not be null");
            }
            if (client.getId() == null || client.getId().isBlank()) {
                throw new IllegalArgumentException("Remote client id must not be blank");
            }
            String protocol = client.getProtocol() != null ? client.getProtocol().toUpperCase(Locale.ROOT) : "";
            if (!"A2A".equals(protocol)) {
                throw new IllegalArgumentException("Remote client protocol must be A2A: " + client.getProtocol());
            }
            if (client.getUrl() == null || client.getUrl().isBlank()) {
                throw new IllegalArgumentException("Remote client url must not be blank");
            }
            if (!isHttpUrl(client.getUrl())) {
                throw new IllegalArgumentException("Remote client url must be an http(s) URL: " + client.getUrl());
            }
            if (client.getTimeoutMs() != null && client.getTimeoutMs() <= 0) {
                throw new IllegalArgumentException("Remote client timeout-ms must be greater than zero");
            }
        }

        private static boolean isHttpUrl(String value) {
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme();
                return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null;
            } catch (URISyntaxException ex) {
                return false;
            }
        }

        private RemotePolicy copy() {
            RemotePolicy copy = copyWithoutClients();
            copy.setClients(clients);
            return copy;
        }

        private RemotePolicy copyWithoutClients() {
            RemotePolicy copy = new RemotePolicy();
            copy.setClients(List.of());
            copy.setTimeoutMs(timeoutMs);
            copy.setRetry(retry.copy());
            copy.setCircuitBreaker(circuitBreaker.copy());
            copy.setAudit(audit.copy());
            copy.setRetryInvoke(shouldRetryInvoke);
            return copy;
        }
    }

    /**
     * Remote client endpoint properties.
     */
    public static class RemoteClientEndpoint {
        private String id;

        private String name;

        private String protocol = "A2A";

        private String url;

        private Integer timeoutMs;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * Sandbox external call policy properties.
     */
    public static class SandboxPolicy implements ExternalCallPolicy {
        private boolean isEnabled = false;

        private List<SandboxServer> servers = new ArrayList<>();

        private int timeoutMs = 30000;

        private ExternalRetryPolicy retry = new ExternalRetryPolicy();

        private ExternalCircuitBreakerPolicy circuitBreaker = new ExternalCircuitBreakerPolicy();

        private ExternalAuditPolicy audit = new ExternalAuditPolicy();

        public boolean isEnabled() {
            return isEnabled;
        }

        public void setEnabled(boolean isEnabled) {
            this.isEnabled = isEnabled;
        }

        public List<SandboxServer> getServers() {
            return servers;
        }

        public void setServers(List<SandboxServer> servers) {
            this.servers = servers != null ? servers : new ArrayList<>();
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("Sandbox timeout-ms must be greater than zero");
            }
            this.timeoutMs = timeoutMs;
        }

        public ExternalRetryPolicy getRetry() {
            return retry;
        }

        public void setRetry(ExternalRetryPolicy retry) {
            this.retry = retry != null ? retry : new ExternalRetryPolicy();
        }

        public ExternalCircuitBreakerPolicy getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(ExternalCircuitBreakerPolicy circuitBreaker) {
            this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new ExternalCircuitBreakerPolicy();
        }

        public ExternalAuditPolicy getAudit() {
            return audit;
        }

        public void setAudit(ExternalAuditPolicy audit) {
            this.audit = audit != null ? audit : new ExternalAuditPolicy();
        }

        /**
         * Validates the configured sandbox servers when sandbox integration is enabled.
         */
        public void validate() {
            validateCallPolicy("Sandbox", timeoutMs, retry, circuitBreaker);
            if (!isEnabled) {
                return;
            }
            if (servers == null || servers.isEmpty()) {
                throw new IllegalArgumentException("Sandbox servers must not be empty when sandbox is enabled");
            }
            for (SandboxServer server : servers) {
                validateServer(server);
            }
        }

        /**
         * Finds the configured sandbox server for the given server id.
         *
         * @param serverId sandbox server id
         * @return configured sandbox server, or empty when no server matches
         */
        public Optional<SandboxServer> findServer(String serverId) {
            if (servers == null || servers.isEmpty()) {
                return Optional.empty();
            }
            if (serverId == null || serverId.isBlank()) {
                return Optional.ofNullable(servers.get(0));
            }
            for (SandboxServer server : servers) {
                if (server != null && serverId.equals(server.getServerId())) {
                    return Optional.of(server);
                }
            }
            return Optional.empty();
        }

        private void validateServer(SandboxServer server) {
            if (server == null) {
                throw new IllegalArgumentException("Sandbox server config must not be null");
            }
            if (server.getServerId() == null || server.getServerId().isBlank()) {
                throw new IllegalArgumentException("Sandbox server-id must not be blank");
            }
            if (server.getServiceUrl() == null || server.getServiceUrl().isBlank()) {
                throw new IllegalArgumentException("Sandbox service-url must not be blank");
            }
            if (!isHttpUrl(server.getServiceUrl())) {
                throw new IllegalArgumentException(
                        "Sandbox service-url must be an http(s) URL: " + server.getServiceUrl());
            }
            if (server.getSandboxType() == null || server.getSandboxType().isBlank()) {
                throw new IllegalArgumentException("Sandbox sandbox-type must not be blank");
            }
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("Sandbox timeout-ms must be greater than zero");
            }
            if (server.getTimeoutMs() != null && server.getTimeoutMs() <= 0) {
                throw new IllegalArgumentException("Sandbox server timeout-ms must be greater than zero");
            }
        }

        private static boolean isHttpUrl(String value) {
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme();
                return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null;
            } catch (URISyntaxException ex) {
                return false;
            }
        }

        private SandboxPolicy copyWithoutServers() {
            SandboxPolicy copy = new SandboxPolicy();
            copy.setEnabled(isEnabled);
            copy.setServers(List.of());
            copy.setTimeoutMs(timeoutMs);
            copy.setRetry(retry.copy());
            copy.setCircuitBreaker(circuitBreaker.copy());
            copy.setAudit(audit.copy());
            return copy;
        }
    }

    /**
     * Sandbox server-specific external call properties.
     */
    public static class SandboxServer {
        private String serverId;

        private String serviceUrl;

        private String sandboxType = "jiuwenbox";

        private String launcherType = "pre_deploy";

        private String onStop = "delete";

        private String rootPath = ".";

        private String isolationKey;

        private String isolationPrefix;

        private ContainerScope containerScope = ContainerScope.SESSION;

        private Integer timeoutMs;

        private Integer idleTtlSeconds;

        private Map<String, Object> params = new LinkedHashMap<>();

        private Map<String, Object> extraParams = new LinkedHashMap<>();

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public String getServiceUrl() {
            return serviceUrl;
        }

        public void setServiceUrl(String serviceUrl) {
            this.serviceUrl = serviceUrl;
        }

        public String getSandboxType() {
            return sandboxType;
        }

        public void setSandboxType(String sandboxType) {
            this.sandboxType = sandboxType;
        }

        public String getLauncherType() {
            return launcherType;
        }

        public void setLauncherType(String launcherType) {
            this.launcherType = launcherType;
        }

        public String getOnStop() {
            return onStop;
        }

        public void setOnStop(String onStop) {
            this.onStop = onStop;
        }

        public String getRootPath() {
            return rootPath;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath;
        }

        public String getIsolationKey() {
            return isolationKey;
        }

        public void setIsolationKey(String isolationKey) {
            this.isolationKey = isolationKey;
        }

        public String getIsolationPrefix() {
            return isolationPrefix;
        }

        public void setIsolationPrefix(String isolationPrefix) {
            this.isolationPrefix = isolationPrefix;
        }

        public ContainerScope getContainerScope() {
            return containerScope;
        }

        public void setContainerScope(ContainerScope containerScope) {
            this.containerScope = containerScope != null ? containerScope : ContainerScope.SESSION;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getIdleTtlSeconds() {
            return idleTtlSeconds;
        }

        public void setIdleTtlSeconds(Integer idleTtlSeconds) {
            this.idleTtlSeconds = idleTtlSeconds;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params != null ? params : new LinkedHashMap<>();
        }

        public Map<String, Object> getExtraParams() {
            return extraParams;
        }

        public void setExtraParams(Map<String, Object> extraParams) {
            this.extraParams = extraParams != null ? extraParams : new LinkedHashMap<>();
        }
    }
}
