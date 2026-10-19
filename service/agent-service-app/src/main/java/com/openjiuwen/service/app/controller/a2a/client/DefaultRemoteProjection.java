/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.app.controller.a2a.A2aPartContent;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Existing Runtime presentation projection, exposed for explicit delegation by custom hooks. */
public class DefaultRemoteProjection implements RemoteProjectionHook {
    @Override
    public Artifact project(StreamingEventKind event, RemoteProjectionContext context) {
        if (event instanceof TaskArtifactUpdateEvent update) {
            return projectArtifact(update, context);
        }
        if (event instanceof TaskStatusUpdateEvent update) {
            return projectStatus(update, context);
        }
        return null;
    }

    private static Artifact projectArtifact(TaskArtifactUpdateEvent update, RemoteProjectionContext context) {
        Artifact original = update.artifact();
        Map<String, Object> metadata = original.metadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(original.metadata());
        metadata.remove(A2aPartContent.TERMINAL_RESULT_METADATA);
        Object existingEvent = metadata.get(RemoteAgentCaller.AGENT_EVENT_METADATA);
        if (existingEvent == null) {
            metadata.put(RemoteAgentCaller.AGENT_EVENT_METADATA,
                    Map.of("type", "output", "source", agentRef(context.remoteAgentName(), update.taskId())));
        }
        String artifactId = existingEvent == null
                ? "remote:" + context.remoteAgentName() + ":" + update.taskId() + ":" + original.artifactId()
                : original.artifactId();
        return Artifact.builder(original).artifactId(artifactId).metadata(metadata).build();
    }

    private static Artifact projectStatus(TaskStatusUpdateEvent update, RemoteProjectionContext context) {
        String normalized = normalizeState(update.status().state());
        List<Part<?>> parts = statusParts(update.status());
        return Artifact.builder()
                .artifactId("status:" + context.remoteAgentName() + ":" + update.taskId() + ":"
                        + java.util.UUID.randomUUID())
                .parts(parts)
                .metadata(Map.of(RemoteAgentCaller.AGENT_EVENT_METADATA,
                        Map.of("type", "status", "source", agentRef(context.remoteAgentName(), update.taskId()),
                                "state", normalized)))
                .build();
    }

    /** Shared state text used by Status projection and its Parts fallback. */
    private static String normalizeState(TaskState state) {
        return state.name().replaceFirst("^TASK_STATE_", "").toLowerCase(Locale.ROOT);
    }

    /** Shared Parts fallback used by Status projection and de-duplication. */
    public static List<Part<?>> statusParts(TaskStatus status) {
        if (status.message() == null || status.message().parts() == null || status.message().parts().isEmpty()) {
            return List.of(new TextPart(normalizeState(status.state())));
        }
        return status.message().parts();
    }

    private static Map<String, Object> agentRef(String agentId, String taskId) {
        return Map.of("agentId", agentId, "taskId", taskId);
    }
}
