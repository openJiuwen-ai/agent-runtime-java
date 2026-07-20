/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.spec.security.AuthMaterial;
import com.openjiuwen.service.spec.security.ExternalTargetRef;
import com.openjiuwen.service.spec.security.TlsMaterial;

import okhttp3.OkHttpClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Prepares outbound TLS/auth material and injectable HTTP clients for agent-core adapters.
 *
 * @since 0.1.0
 */
public class ExternalOutboundSecuritySupport {
    private final ExternalTlsConfigResolver tlsConfigResolver;

    private final ExternalAuthMaterialMerger authMaterialMerger;

    private final ExternalHttpClientFactory httpClientFactory;

    public ExternalOutboundSecuritySupport(ExternalTlsConfigResolver tlsConfigResolver,
        ExternalAuthMaterialMerger authMaterialMerger, ExternalHttpClientFactory httpClientFactory) {
        this.tlsConfigResolver = tlsConfigResolver;
        this.authMaterialMerger = authMaterialMerger;
        this.httpClientFactory = httpClientFactory;
    }

    /**
     * Prepares outbound security for an external endpoint.
     *
     * @param target outbound target
     * @param tlsConfig TLS configuration
     * @param authConfig auth configuration
     * @param connectTimeout connect timeout
     * @return prepared outbound security
     */
    public PreparedOutboundSecurity prepare(ExternalTargetRef target, ExternalTlsConfig tlsConfig,
        ExternalAuthProperties authConfig, Duration connectTimeout) {
        Optional<TlsMaterial> tlsMaterial = tlsConfigResolver.resolve(tlsConfig);
        AuthMaterial authMaterial = authMaterialMerger.merge(target, authConfig != null
            ? authConfig.toSpecConfig()
            : com.openjiuwen.service.spec.security.ExternalAuthConfig.none());

        HttpClient jdkClient = tlsMaterial.map(material -> httpClientFactory.createJdkClient(material, connectTimeout))
            .orElse(null);
        OkHttpClient okHttpClient = null;
        if (tlsMaterial.isPresent() || (authMaterial != null && !authMaterial.isEmpty())) {
            okHttpClient = httpClientFactory.createOkHttpClient(tlsMaterial.orElse(null), authMaterial, connectTimeout);
        }

        ExternalSecurityContext context = new ExternalSecurityContext(target.adapterType(), target.targetId(),
            tlsConfig != null && tlsConfig.isEnabled(), authConfig != null ? authConfig.getType() : "none",
            authMaterial.materialExtensions());

        return new PreparedOutboundSecurity(tlsMaterial, authMaterial, context, jdkClient, okHttpClient);
    }

    /**
     * Creates a default outbound security support chain for tests and manual wiring.
     *
     * @param credentialDecryptor credential decryptor
     * @return default outbound security support
     */
    public static ExternalOutboundSecuritySupport createDefault(
        com.openjiuwen.service.adapters.common.credential.CredentialDecryptor credentialDecryptor) {
        ExternalTlsConfigResolver tlsConfigResolver = new ExternalTlsConfigResolver(new GlobalTlsProperties(),
            credentialDecryptor);
        ExternalAuthMaterialMerger authMaterialMerger = new ExternalAuthMaterialMerger(new NoOpExternalAuthenticator(),
            credentialDecryptor);
        ExternalHttpClientFactory httpClientFactory = new ExternalHttpClientFactory(
            new org.springframework.core.io.DefaultResourceLoader());
        return new ExternalOutboundSecuritySupport(tlsConfigResolver, authMaterialMerger, httpClientFactory);
    }

    /**
     * Prepared outbound security artifacts for adapter registration.
     *
     * @param tlsMaterial optional TLS material
     * @param authMaterial merged auth overlay
     * @param securityContext audit context
     * @param jdkHttpClient optional JDK HTTP client
     * @param okHttpClient optional OkHttp client
     */
    public record PreparedOutboundSecurity(Optional<TlsMaterial> tlsMaterial, AuthMaterial authMaterial,
        ExternalSecurityContext securityContext, HttpClient jdkHttpClient, OkHttpClient okHttpClient) {

        /**
         * Applies auth headers and query params to MCP core config fields.
         *
         * @param authHeaders mutable auth header map
         * @param authQueryParams mutable auth query map
         */
        public void applyAuthToMaps(Map<String, String> authHeaders, Map<String, String> authQueryParams) {
            if (authMaterial == null || authMaterial.isEmpty()) {
                return;
            }
            authHeaders.putAll(authMaterial.headers());
            authQueryParams.putAll(authMaterial.queryParams());
        }

        /**
         * Injects security params into a mutable params map.
         *
         * @param params mutable params map
         */
        public void injectParams(Map<String, Object> params) {
            if (params == null) {
                return;
            }
            if (jdkHttpClient != null) {
                params.put(ExternalOutboundSecurityConstants.PARAM_HTTP_CLIENT, jdkHttpClient);
            }
            if (okHttpClient != null) {
                params.put(ExternalOutboundSecurityConstants.PARAM_OKHTTP_CLIENT, okHttpClient);
            }
        }

        /**
         * Injects security kwargs into a mutable remote-client kwargs map.
         *
         * @param kwargs mutable kwargs map
         */
        public void injectRemoteKwargs(Map<String, Object> kwargs) {
            if (kwargs == null) {
                return;
            }
            if (jdkHttpClient != null) {
                kwargs.put(ExternalOutboundSecurityConstants.KWARG_HTTP_CLIENT, jdkHttpClient);
            }
            if (authMaterial != null && !authMaterial.isEmpty()) {
                kwargs.put(ExternalOutboundSecurityConstants.KWARG_AUTH_HEADERS,
                    new LinkedHashMap<>(authMaterial.headers()));
                kwargs.put(ExternalOutboundSecurityConstants.KWARG_AUTH_QUERY_PARAMS,
                    new LinkedHashMap<>(authMaterial.queryParams()));
            }
        }
    }
}
