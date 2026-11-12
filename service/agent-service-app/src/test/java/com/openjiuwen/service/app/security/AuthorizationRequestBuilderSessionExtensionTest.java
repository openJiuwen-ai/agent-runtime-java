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

/**
 * Tests for the session-id extension extraction of the authorization request
 * builder (issue #117 resource-level authorization).
 *
 * @since 0.1.5
 */
class AuthorizationRequestBuilderSessionExtensionTest {
    private static final AuthorizedResource PAUSE_ANNOTATION = pauseAnnotation();

    private static AuthorizedResource pauseAnnotation() {
        try {
            return SessionManageControllerStub.class.getDeclaredMethod("pause")
                    .getAnnotation(AuthorizedResource.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("stub method missing", exception);
        }
    }

    @Test
    void sessionScopedPathInjectsResourceIdExtension() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "op-1");

        AuthorizationRequest request = AuthorizationRequestBuilder.build(PAUSE_ANNOTATION, headers,
                "/api/sessions/session-42/pause");

        assertThat(request.extensions())
                .containsEntry(AgentServicePaths.EXTENSION_RESOURCE_ID, "session-42");
        assertThat(request.userId()).isEqualTo("op-1");
    }

    @Test
    void detailPathInjectsResourceIdExtension() {
        AuthorizationRequest request = AuthorizationRequestBuilder.build(PAUSE_ANNOTATION, new HttpHeaders(),
                "/api/sessions/session-7");

        assertThat(request.extensions())
                .containsEntry(AgentServicePaths.EXTENSION_RESOURCE_ID, "session-7");
    }

    @Test
    void nonSessionPathKeepsEmptyExtensions() {
        AuthorizationRequest request = AuthorizationRequestBuilder.build(PAUSE_ANNOTATION, new HttpHeaders(),
                "/v1/query");

        assertThat(request.extensions()).isEmpty();
    }

    @Test
    void nullLookupPathKeepsEmptyExtensions() {
        AuthorizationRequest request = AuthorizationRequestBuilder.build(PAUSE_ANNOTATION, new HttpHeaders(), null);

        assertThat(request.extensions()).isEmpty();
    }

    private static final class SessionManageControllerStub {
        @AuthorizedResource(resource = "session", action = "pause")
        void pause() {
            // Annotation carrier only.
        }
    }
}
