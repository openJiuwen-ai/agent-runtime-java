/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport.RecordingFineGrainedAuthorizer;
import com.openjiuwen.service.spec.security.AuthorizationRequest;
import com.openjiuwen.service.spec.security.AuthorizationResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

/**
 * TC-AUTHZ-08: authorization tolerates unusual tenant header values without crashing.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(SecurityAuthorizationHeaderBoundaryIntegrationTest.DenySuspiciousUserAuthorizerConfig.class)
@TestPropertySource(properties = {
    "openjiuwen.service.security.enabled=true",
    "openjiuwen.service.security.auth.enabled=true"
})
class SecurityAuthorizationHeaderBoundaryIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingFineGrainedAuthorizer recordingAuthorizer;

    @Test
    void unusualUserIdHeaderIsPassedToSpiWithoutInternalError() {
        recordingAuthorizer.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", "<script>alert(1)</script>");
        Map<String, Object> body = Map.of("conversation_id", "conv-header-boundary", "messages",
            List.of(Map.of("role", "user", "content", "hello")), "stream", false);

        ResponseEntity<String> response = rest.postForEntity("/v1/query", new HttpEntity<>(body, headers),
            String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(recordingAuthorizer.requests()).hasSize(1);
        AuthorizationRequest authRequest = recordingAuthorizer.requests().get(0);
        assertThat(authRequest.userId()).isEqualTo("<script>alert(1)</script>");
    }

    @TestConfiguration
    static class DenySuspiciousUserAuthorizerConfig {
        @Bean
        RecordingFineGrainedAuthorizer recordingFineGrainedAuthorizer() {
            return new RecordingFineGrainedAuthorizer(request -> {
                if (request.userId() != null && request.userId().contains("<")) {
                    return AuthorizationResult.deny("suspicious user id");
                }
                return AuthorizationResult.allow();
            });
        }
    }
}
