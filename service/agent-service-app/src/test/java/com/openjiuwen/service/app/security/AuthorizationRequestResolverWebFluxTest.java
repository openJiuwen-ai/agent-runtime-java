/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

/**
 * Tests for the reactive authorization request resolution (issue #117
 * resource-level authorization on the WebFlux stack): the request path is
 * rebuilt from the handler method's mapping pattern and {@code @PathVariable}
 * values so session-scoped reactive endpoints carry the {@code resource.id}
 * extension.
 *
 * @since 0.1.5
 */
class AuthorizationRequestResolverWebFluxTest {

    @Test
    void reactiveSessionPauseResolvesResourceIdExtension() throws Exception {
        AuthorizedResource annotation = ReactiveSessionStub.class
                .getDeclaredMethod("pause", HttpHeaders.class, String.class)
                .getAnnotation(AuthorizedResource.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "op-flux");

        AuthorizationRequest request = AuthorizationRequestResolver.resolve(
                joinPointOf(new ReactiveSessionStub(), "pause", headers, "session-77"), annotation);

        assertThat(request.extensions())
                .containsEntry(AgentServicePaths.EXTENSION_RESOURCE_ID, "session-77");
        assertThat(request.userId()).isEqualTo("op-flux");
    }

    @Test
    void reactiveNonSessionEndpointKeepsEmptyExtensions() throws Exception {
        AuthorizedResource annotation = ReactiveSessionStub.class
                .getDeclaredMethod("query", HttpHeaders.class)
                .getAnnotation(AuthorizedResource.class);

        AuthorizationRequest request = AuthorizationRequestResolver.resolve(
                joinPointOf(new ReactiveSessionStub(), "query", new HttpHeaders()), annotation);

        assertThat(request.extensions()).isEmpty();
    }

    @Test
    void joinPointWithoutHeadersFailsClosed() throws Exception {
        AuthorizedResource annotation = ReactiveSessionStub.class
                .getDeclaredMethod("pause", HttpHeaders.class, String.class)
                .getAnnotation(AuthorizedResource.class);

        assertThatThrownBy(() -> AuthorizationRequestResolver.resolve(
                joinPointOf(new ReactiveSessionStub(), "pause", "x", "session-1"), annotation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ingress request context");
    }

    private static ProceedingJoinPoint joinPointOf(Object target, String methodName, Object... args)
            throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                return new StubJoinPoint(target, method, args);
            }
        }
        throw new IllegalStateException("stub method not found: " + methodName);
    }

    /** Minimal join point exposing only what the resolver consumes. */
    private static final class StubJoinPoint implements ProceedingJoinPoint {
        private final Object target;
        private final Method declared;
        private final Object[] args;

        StubJoinPoint(Object target, Method declared, Object[] args) {
            this.target = target;
            this.declared = declared;
            this.args = args;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return new Signature() {
                @Override
                public String getName() {
                    return declared.getName();
                }

                @Override
                public int getModifiers() {
                    return declared.getModifiers();
                }

                @Override
                public Class<?> getDeclaringType() {
                    return target.getClass();
                }

                @Override
                public String getDeclaringTypeName() {
                    return target.getClass().getName();
                }

                @Override
                public String toShortString() {
                    return declared.getName();
                }

                @Override
                public String toLongString() {
                    return declared.toGenericString();
                }

                @Override
                public String toString() {
                    return declared.toGenericString();
                }
            };
        }

        @Override
        public Object proceed() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Object proceed(Object[] arguments) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void set$AroundClosure(org.aspectj.runtime.internal.AroundClosure closure) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Object getThis() {
            return target;
        }

        @Override
        public org.aspectj.lang.reflect.SourceLocation getSourceLocation() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getKind() {
            return JoinPoint.METHOD_EXECUTION;
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String toShortString() {
            return declared.getName();
        }

        @Override
        public String toLongString() {
            return declared.toGenericString();
        }

        @Override
        public String toString() {
            return declared.toGenericString();
        }
    }

    /** Reactive controller stub mirroring the session endpoint signatures. */
    static class ReactiveSessionStub {
        @PostMapping("/api/sessions/{sessionId}/pause")
        @AuthorizedResource(resource = "session", action = "pause")
        void pause(HttpHeaders headers, @PathVariable("sessionId") String sessionId) {
        }

        @GetMapping("/v1/query/reactive")
        @AuthorizedResource(resource = "query", action = "execute")
        void query(HttpHeaders headers) {
        }
    }
}
