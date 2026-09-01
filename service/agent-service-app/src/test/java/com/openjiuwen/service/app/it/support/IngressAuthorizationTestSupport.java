/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizationResult;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared helpers for ingress authorization integration tests.
 *
 * @since 0.1.0
 */
public final class IngressAuthorizationTestSupport {
    private IngressAuthorizationTestSupport() {
    }

    /**
     * Builds an authorizer that denies a single resource/action pair.
     *
     * @param resource resource name
     * @param action action name
     * @param reason denial reason
     * @return authorizer bean logic
     */
    public static FineGrainedAuthorizer denyWhen(String resource, String action, String reason) {
        return request -> resource.equals(request.resource()) && action.equals(request.action())
            ? AuthorizationResult.deny(reason)
            : AuthorizationResult.allow();
    }

    /**
     * Builds an authorizer that denies selected {@code resource:action} pairs.
     *
     * @param resourceActionPairs pairs as {@code resource, action, resource, action, ...}
     * @param reason denial reason
     * @return authorizer bean logic
     */
    public static FineGrainedAuthorizer denyWhenAny(String reason, String... resourceActionPairs) {
        if (resourceActionPairs.length % 2 != 0) {
            throw new IllegalArgumentException("resourceActionPairs must contain resource/action couples");
        }
        Set<String> denied = new HashSet<>();
        for (int index = 0; index < resourceActionPairs.length; index += 2) {
            denied.add(resourceActionPairs[index] + ":" + resourceActionPairs[index + 1]);
        }
        return request -> denied.contains(request.resource() + ":" + request.action())
            ? AuthorizationResult.deny(reason)
            : AuthorizationResult.allow();
    }

    /**
     * Asserts the ingress 403 JSON contract.
     *
     * @param mapper JSON mapper
     * @param response HTTP response
     * @param resource expected resource
     * @param action expected action
     * @param reason expected reason
     * @throws Exception if JSON parsing fails
     */
    @SuppressWarnings("unchecked")
    public static void assertAccessDenied(ObjectMapper mapper, ResponseEntity<String> response, String resource,
        String action, String reason) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> error = mapper.readValue(response.getBody(), Map.class);
        assertThat(error.get("type")).isEqualTo("error");
        assertThat(error.get("code")).isEqualTo("ACCESS_DENIED");
        assertThat(error.get("resource")).isEqualTo(resource);
        assertThat(error.get("action")).isEqualTo(action);
        assertThat(error.get("reason")).isEqualTo(reason);
    }

    /**
     * Records authorization requests for integration assertions.
     */
    public static final class RecordingFineGrainedAuthorizer implements FineGrainedAuthorizer {
        private final FineGrainedAuthorizer delegate;

        private final List<AuthorizationRequest> requests = new CopyOnWriteArrayList<>();

        public RecordingFineGrainedAuthorizer(FineGrainedAuthorizer delegate) {
            this.delegate = delegate;
        }

        @Override
        public AuthorizationResult authorize(AuthorizationRequest request) {
            requests.add(request);
            return delegate.authorize(request);
        }

        /**
         * Returns captured authorization requests.
         *
         * @return immutable snapshot of requests
         */
        public List<AuthorizationRequest> requests() {
            return List.copyOf(requests);
        }

        /**
         * Clears captured requests.
         */
        public void clear() {
            requests.clear();
        }
    }

    /**
     * Spring test configuration that allows all annotated ingress resources.
     */
    @TestConfiguration
    public static class AllowAnnotatedIngressAuthorizerConfig {
        /**
         * Allows all ingress resources for integration testing.
         *
         * @return recording authorizer bean
         */
        @Bean
        RecordingFineGrainedAuthorizer recordingFineGrainedAuthorizer() {
            return new RecordingFineGrainedAuthorizer(request -> AuthorizationResult.allow());
        }
    }

    /**
     * Spring test configuration that allows {@code query:execute}.
     */
    @TestConfiguration
    public static class AllowQueryAuthorizerConfig {
        /**
         * Allows query execute for integration testing.
         *
         * @return recording authorizer bean
         */
        @Bean
        RecordingFineGrainedAuthorizer recordingFineGrainedAuthorizer() {
            return new RecordingFineGrainedAuthorizer(request -> AuthorizationResult.allow());
        }
    }

    /**
     * Spring test configuration that denies {@code query:execute}.
     */
    @TestConfiguration
    public static class DenyQueryAuthorizerConfig {
        /**
         * Denies query execute for integration testing.
         *
         * @return authorizer bean
         */
        @Bean
        FineGrainedAuthorizer fineGrainedAuthorizer() {
            return denyWhen("query", "execute", "policy denied");
        }
    }

    /**
     * Spring test configuration that denies annotated ingress endpoints used in TC-AUTHZ / TC-A2A.
     */
    @TestConfiguration
    public static class DenyAnnotatedIngressAuthorizerConfig {
        /**
         * Denies selected ingress resources for integration testing.
         *
         * @return authorizer bean
         */
        @Bean
        FineGrainedAuthorizer fineGrainedAuthorizer() {
            return denyWhenAny("policy denied", "agent-card", "read", "session", "reset", "a2a", "rpc");
        }
    }
}
