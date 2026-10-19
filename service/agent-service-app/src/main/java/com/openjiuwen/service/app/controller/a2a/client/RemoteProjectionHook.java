/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.StreamingEventKind;

/**
 * Projects a remote A2A event to the Artifact shown by the local runtime.
 *
 * <p>Returning {@code null} hides the event from the presentation stream. The
 * original event and its aggregated Task remain unchanged. Implementations must
 * treat the input as read-only, including nested collections and metadata, and
 * create independent presentation data when editing it.</p>
 *
 * @since 0.1.4
 */
@FunctionalInterface
public interface RemoteProjectionHook {
    /**
     * Projects one remote event.
     *
     * @param event remote event or a framework-created delegation event
     * @param context read-only invocation context
     * @return presentation Artifact, or {@code null} to hide it
     */
    Artifact project(StreamingEventKind event, RemoteProjectionContext context);
}
