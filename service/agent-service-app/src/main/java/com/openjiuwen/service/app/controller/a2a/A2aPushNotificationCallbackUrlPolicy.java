/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Runtime-to-runtime callback URL checks shared by inbound validation and outbound delivery.
 */
final class A2aPushNotificationCallbackUrlPolicy {
    private A2aPushNotificationCallbackUrlPolicy() {
    }

    static void validateCallbackUrl(TaskPushNotificationConfig config) {
        if (config == null) {
            return;
        }
        if (callbackUri(config.url()).isEmpty()) {
            throw new InvalidParamsError("Invalid params: invalid push notification callbackUrl");
        }
    }

    static Optional<URI> callbackUri(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(callbackUrl);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null || uri.getHost() == null || uri.getHost().isBlank()
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
