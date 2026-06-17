/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps AgentCore interrupt signals to service-layer query events.
 */
final class InterruptEventMapper {

    private InterruptEventMapper() {
    }

    static Map<String, Object> fromException(ToolInterruptException ex) {
        return fromRequest(ex.getRequest());
    }

    static Map<String, Object> fromRequest(InterruptRequest request) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "interrupt");
        if (request != null) {
            event.put("interrupt_id", request.getInterruptId());
            event.put("message", request.getMessage());
            if (request.getContext() != null && !request.getContext().isEmpty()) {
                event.put("context", request.getContext());
            }
            if (request.getPayloadSchema() != null && !request.getPayloadSchema().isEmpty()) {
                event.put("payload_schema", request.getPayloadSchema());
            }
            if (request.getAutoConfirmKey() != null) {
                event.put("auto_confirm_key", request.getAutoConfirmKey());
            }
        }
        event.put("suspend", true);
        return event;
    }

    static boolean isInterruptPayload(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return false;
        }
        Object type = map.get("type");
        if ("interrupt".equals(String.valueOf(type))) {
            return true;
        }
        Object state = firstNonNull(map.get("state"), map.get("execution_state"));
        return "INPUT_REQUIRED".equals(String.valueOf(state));
    }

    static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
