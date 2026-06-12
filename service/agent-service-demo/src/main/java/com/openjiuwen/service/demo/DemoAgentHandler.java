/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.LinkedHashMap;
import java.util.Map;

class DemoAgentHandler implements AgentHandler {

    @Override
    public QueryResponse query(ServeRequest request) {
        return new QueryResponse(result(request), request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        observer.onNext(new QueryChunk("chunk", result(request)));
        observer.onComplete();
    }

    private static Map<String, Object> result(ServeRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", "demo:" + request.lastUserQuery());
        result.put("conversation_id", request.getConversationId());
        return result;
    }
}
