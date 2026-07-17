/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

/**
 * Unit tests for {@link AuthorizationRequestBuilder}.
 *
 * @since 0.1.0
 */
class AuthorizationRequestBuilderTest {
    @Test
    void buildIncludesResourceActionHeadersAndExtensions() throws Exception {
        Method method = SampleEndpoints.class.getDeclaredMethod("query");
        AuthorizedResource annotation = method.getAnnotation(AuthorizedResource.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/query");
        request.addHeader("X-User-ID", "user-1");
        request.addHeader("X-Space-ID", "space-1");
        request.addHeader("X-Tenant-ID", "tenant-1");
        request.setRemoteAddr("127.0.0.1");

        AuthorizationRequest built = AuthorizationRequestBuilder.build(annotation, request);

        assertThat(built.resource()).isEqualTo("query");
        assertThat(built.action()).isEqualTo("execute");
        assertThat(built.userId()).isEqualTo("user-1");
        assertThat(built.spaceId()).isEqualTo("space-1");
        assertThat(built.tenantId()).isEqualTo("tenant-1");
        assertThat(built.extensions()).containsEntry("httpMethod", "POST");
        assertThat(built.extensions()).containsEntry("requestPath", "/v1/query");
        assertThat(built.extensions()).containsEntry("clientIp", "127.0.0.1");
    }

    static class SampleEndpoints {
        @AuthorizedResource(resource = "query", action = "execute")
        void query() {
        }
    }
}
