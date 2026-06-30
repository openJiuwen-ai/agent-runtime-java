/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demol1test.health;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Internal test helper: minimal Agent Service for health probe validation.
 */
@SpringBootApplication
public class HealthProbeExample {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(HealthProbeExample.class);
        application.setDefaultProperties(Map.of(
                "server.port", "8090",
                "spring.main.web-application-type", "servlet",
                "spring.application.name", "health-probe-example",
                "openjiuwen.service.version", "0.1.0"
        ));
        application.run(args);
    }

    @Bean
    AgentHandler healthExampleAgentHandler() {
        return new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest request) {
                return new QueryResponse(responseBody(request), request.getConversationId());
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                observer.onNext(new QueryChunk("chunk", responseBody(request)));
                observer.onComplete();
            }
        };
    }

    private static Map<String, Object> responseBody(ServeRequest request) {
        return Map.of(
                "role", "assistant",
                "content", "health-example:" + request.lastUserQuery(),
                "conversation_id", request.getConversationId()
        );
    }
}
