/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Unit tests for WebFlux authorization request assembly.
 */
class AuthorizationRequestBuilderWebFluxTest {
    @Test
    void buildFromHeadersUsesTenantHeadersAndMappingMetadata() throws Exception {
        AuthorizedResource annotation = SampleReactiveEndpoints.class.getDeclaredMethod("queryReactive")
            .getAnnotation(AuthorizedResource.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-ID", "flux-user");
        headers.add("X-Space-ID", "flux-space");
        headers.add("X-Tenant-ID", "flux-tenant");

        AuthorizationRequest built = AuthorizationRequestBuilder.build(annotation, headers, "POST",
            AgentServicePaths.QUERY_V1_REACTIVE, "10.0.0.8");

        assertThat(built.resource()).isEqualTo("query");
        assertThat(built.action()).isEqualTo("execute");
        assertThat(built.userId()).isEqualTo("flux-user");
        assertThat(built.spaceId()).isEqualTo("flux-space");
        assertThat(built.tenantId()).isEqualTo("flux-tenant");
        assertThat(built.extensions()).containsEntry("httpMethod", "POST");
        assertThat(built.extensions()).containsEntry("requestPath", AgentServicePaths.QUERY_V1_REACTIVE);
        assertThat(built.extensions()).containsEntry("clientIp", "10.0.0.8");
    }

    static class SampleReactiveEndpoints {
        @PostMapping(AgentServicePaths.QUERY_V1_REACTIVE)
        @AuthorizedResource(resource = "query", action = "execute")
        void queryReactive() {
        }
    }
}
