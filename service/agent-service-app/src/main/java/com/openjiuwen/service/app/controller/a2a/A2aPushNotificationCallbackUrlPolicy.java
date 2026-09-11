/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;

/**
 * Runtime-to-runtime callback URL checks shared by inbound validation and outbound delivery.
 */
final class A2aPushNotificationCallbackUrlPolicy {
    private A2aPushNotificationCallbackUrlPolicy() {
    }

    static void validateCallbackUrl(TaskPushNotificationConfig config, List<String> allowedHosts) {
        if (config == null) {
            return;
        }
        if (callbackUri(config.url(), allowedHosts).isEmpty()) {
            throw new InvalidParamsError("Invalid params: invalid push notification callbackUrl");
        }
    }

    static Optional<URI> callbackUri(String callbackUrl, List<String> allowedHosts) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(callbackUrl);
            String scheme = uri.getScheme();
            if (!hasValidScheme(uri, scheme) || !hasValidHost(uri) || !hasValidComponents(uri)) {
                return Optional.empty();
            }
            if (allowedHosts.stream().anyMatch(uri.getHost()::equalsIgnoreCase)) {
                return Optional.of(uri);
            }
            // Rechecked before delivery. JDK HttpClient resolves again when connecting;
            // deployment egress controls are still required against DNS rebinding.
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                return Optional.empty();
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    return Optional.empty();
                }
            }
            return Optional.of(uri);
        } catch (URISyntaxException | UnknownHostException e) {
            return Optional.empty();
        }
    }

    private static boolean hasValidScheme(URI uri, String scheme) {
        return uri.isAbsolute() && scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    }

    private static boolean hasValidHost(URI uri) {
        return uri.getHost() != null && !uri.getHost().isBlank();
    }

    private static boolean hasValidComponents(URI uri) {
        return uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && uri.getPort() != 0 && uri.getPort() <= 65535;
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            // Also reject 0/8, shared address space (including 100.100.100.200), and reserved 240/4.
            return first != 0 && first < 240 && !(first == 100 && second >= 64 && second <= 127);
        }
        return (first & 0xfe) != 0xfc; // IPv6 unique-local addresses (fc00::/7).
    }
}
