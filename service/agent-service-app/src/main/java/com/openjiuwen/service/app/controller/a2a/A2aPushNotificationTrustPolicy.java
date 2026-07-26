/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;

import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Runtime-to-runtime callback target trust checks shared by inbound validation and outbound delivery.
 */
final class A2aPushNotificationTrustPolicy {
    private A2aPushNotificationTrustPolicy() {
    }

    static void validateTrusted(TaskPushNotificationConfig config,
            A2AProperties.PushNotificationProperties properties) {
        if (config == null) {
            return;
        }
        if (trustedCallbackUri(config.url(), properties).isEmpty()) {
            throw new InvalidParamsError("Invalid params: untrusted push notification callbackUrl");
        }
    }

    static Optional<URI> trustedCallbackUri(String callbackUrl, A2AProperties.PushNotificationProperties properties) {
        Optional<URI> uri = parseCallbackUri(callbackUrl);
        if (uri.isEmpty() || properties == null) {
            return Optional.empty();
        }
        List<String> trustedHosts = properties.getTrustedCallbackHosts();
        if (trustedHosts == null || trustedHosts.isEmpty()) {
            return Optional.empty();
        }
        String host = uri.get().getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean isTrusted = trustedHosts.stream().filter(candidate -> candidate != null && !candidate.isBlank())
                .map(candidate -> candidate.toLowerCase(Locale.ROOT)).anyMatch(normalizedHost::equals);
        return isTrusted ? uri : Optional.empty();
    }

    private static Optional<URI> parseCallbackUri(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(callbackUrl);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
