/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.app.config.A2AProperties;

import org.a2aproject.sdk.server.tasks.PushNotificationSender;

/**
 * Computes the externally advertised A2A push-notification capability.
 */
public class A2aPushNotificationCapabilityGate {
    private final A2AProperties properties;

    private final PushNotificationSender sender;

    private final A2aPushNotificationCallbackStore callbackStore;

    private final A2aPushNotificationCallbackHandler callbackHandler;

    public A2aPushNotificationCapabilityGate(A2AProperties properties, PushNotificationSender sender,
            A2aPushNotificationCallbackStore callbackStore, A2aPushNotificationCallbackHandler callbackHandler) {
        this.properties = properties;
        this.sender = sender;
        this.callbackStore = callbackStore;
        this.callbackHandler = callbackHandler;
    }

    /**
     * Returns true only when the local runtime can complete both push delivery and callback recovery.
     *
     * @return whether Agent Card should advertise pushNotifications
     */
    public boolean isPushNotificationsEnabled() {
        return properties.isPushNotifications()
            && properties.getPushNotification() != null
            && properties.getPushNotification().getTrustedCallbackHosts() != null
            && !properties.getPushNotification().getTrustedCallbackHosts().isEmpty()
            && sender instanceof HttpPushNotificationSender
            && callbackStore != null
            && callbackHandler != null
            && !(callbackHandler instanceof NoOpA2aPushNotificationCallbackHandler);
    }
}
