/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Unit tests for WebFlux authorization request assembly via
 * {@link AuthorizationRequestBuilder}.
 *
 * @since 0.1.0
 */
class AuthorizationRequestBuilderWebFluxTest {
    @Test
    void buildFromHeadersUsesTenantHeaders() throws Exception {
        AuthorizedResource annotation = SampleReactiveEndpoints.class.getDeclaredMethod("queryReactive")
            .getAnnotation(AuthorizedResource.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-ID", "flux-user");
        headers.add("X-Space-ID", "flux-space");
        headers.add("X-Tenant-ID", "flux-tenant");

        AuthorizationRequest built = AuthorizationRequestBuilder.build(annotation, headers);

        assertThat(built.resource()).isEqualTo("query");
        assertThat(built.action()).isEqualTo("execute");
        assertThat(built.userId()).isEqualTo("flux-user");
        assertThat(built.spaceId()).isEqualTo("flux-space");
        assertThat(built.tenantId()).isEqualTo("flux-tenant");
        assertThat(built.extensions()).isEmpty();
    }

    static class SampleReactiveEndpoints {
        @PostMapping("/v1/query/reactive")
        @AuthorizedResource(resource = "query", action = "execute")
        void queryReactive() {
        }
    }
}
