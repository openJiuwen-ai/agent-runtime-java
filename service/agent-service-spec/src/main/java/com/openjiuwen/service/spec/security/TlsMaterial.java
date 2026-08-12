/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

import java.util.List;

/**
 * Immutable TLS keystore/truststore material shared by ingress ({@code server.ssl})
 * and egress HTTP clients (Issue #25).
 *
 * @param keyStoreLocation classpath or file location of the key store
 * @param keyStorePassword key store password
 * @param keyStoreType key store type (for example {@code PKCS12})
 * @param trustStoreLocation classpath or file location of the trust store
 * @param trustStorePassword trust store password
 * @param trustStoreType trust store type
 * @param enabledProtocols enabled TLS protocol names
 * @param verifyHostname whether hostname verification is required for outbound use
 * @since 0.1.0
 */
public record TlsMaterial(String keyStoreLocation, char[] keyStorePassword, String keyStoreType,
    String trustStoreLocation, char[] trustStorePassword, String trustStoreType, List<String> enabledProtocols,
    boolean verifyHostname) {
}
