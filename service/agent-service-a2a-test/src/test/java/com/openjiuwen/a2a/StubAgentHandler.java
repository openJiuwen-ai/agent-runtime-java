/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */


package com.openjiuwen.a2a;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stub {@link AgentHandler} for A2A protocol tests — no LLM dependency.
 * Returns fixed responses, enabling deterministic A2A integration testing.
 */
class StubAgentHandler implements AgentHandler {

    @Override
    public QueryResponse query(ServeRequest request) {
        return new QueryResponse(stubResult(request), request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        observer.onNext(new QueryChunk("chunk", stubResult(request)));
        observer.onComplete();
    }

    private static Map<String, Object> stubResult(ServeRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", "stub:" + request.lastUserQuery());
        result.put("conversation_id", request.getConversationId());
        return result;
    }
}
