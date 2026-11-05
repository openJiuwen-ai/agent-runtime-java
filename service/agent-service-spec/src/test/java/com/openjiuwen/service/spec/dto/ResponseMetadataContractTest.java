/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies JSON and callback compatibility for response metadata contracts.
 *
 * @since 2026-10-07
 */
class ResponseMetadataContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emptyMetadataPreservesOriginalResponseJson() throws Exception {
        QueryResponse response = new QueryResponse("done", "conversation");
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).has("metadata")).isFalse();
        response.setMetadata(Map.of());
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).has("metadata")).isFalse();
        response.setMetadata(Map.of("leadOut", "next"));
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).path("metadata").path("leadOut").asText())
                .isEqualTo("next");
    }

    @Test
    void remoteMetadataCannotEnterRequestJsonOrBeSuppliedByIngress() throws Exception {
        ServeRequest request = new ServeRequest();
        request.setMetadata(Map.of("userAgent", "mobile"));
        request.setRemoteResponseMetadata(Map.of("call-1", Map.of("secret", "response-only")));
        String json = mapper.writeValueAsString(request);
        assertThat(json).contains("userAgent").doesNotContain("remoteResponseMetadata", "response-only");
        ServeRequest restored = mapper.readValue("""
                {"remoteResponseMetadata":{"call-1":{"forged":true}}}
                """, ServeRequest.class);
        assertThat(restored.getRemoteResponseMetadata()).isEmpty();
    }

    @Test
    void newCompletionCallbackDelegatesToLegacyObserver() {
        AtomicInteger completed = new AtomicInteger();
        QueryStreamObserver observer = new QueryStreamObserver() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void onNext(QueryChunk chunk) {
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onComplete() {
                completed.incrementAndGet();
            }
        };
        observer.onComplete(new QueryResponse(null, "conversation", Map.of("leadOut", "next")));
        assertThat(completed.get()).isEqualTo(1);
    }
}
