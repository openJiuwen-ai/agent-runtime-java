/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.examples;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal custom {@link AgentHandler} template for third-party runtimes (demo / reference only).
 *
 * <p>Register with {@code @Bean AgentHandler} in the business application, or copy and adapt
 * for HTTP proxy / remote workflow bridges. Does not call Core {@code Runner}.</p>
 */
public class EchoProxyAgentHandler implements AgentHandler {

    private final String prefix;

    public EchoProxyAgentHandler() {
        this.prefix = "proxy:";
    }

    public EchoProxyAgentHandler(String prefix) {
        this.prefix = prefix == null || prefix.isBlank() ? "proxy:" : prefix;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        return new QueryResponse(buildResult(request), request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        observer.onNext(new QueryChunk("chunk", buildResult(request)));
        observer.onComplete();
    }

    private Map<String, Object> buildResult(ServeRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", prefix + request.lastUserQuery());
        result.put("handler", "echo-proxy");
        return result;
    }
}
