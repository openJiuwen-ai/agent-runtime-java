/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.it.support.IngressAuthorizationTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

/**
 * WebFlux authorization deny-path integration tests for {@code /v1/query/reactive}
 * against {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(IngressAuthorizationTestSupport.DenyQueryAuthorizerConfig.class)
@TestPropertySource(properties = {
    "spring.main.web-application-type=reactive",
    "openjiuwen.service.query.webflux.enabled=true",
    "openjiuwen.service.security.enabled=true",
    "openjiuwen.service.security.auth.enabled=true"
})
class SecurityWebFluxAuthorizationIntegrationTest {
    @Autowired
    private WebTestClient webTestClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void denyReactiveQueryReturns403Contract() throws Exception {
        Map<String, Object> body = Map.of("conversation_id", "conv-flux-auth-deny", "messages",
            List.of(Map.of("role", "user", "content", "hello")), "stream", false);

        byte[] bytes = webTestClient.post().uri("/v1/query/reactive").contentType(MediaType.APPLICATION_JSON)
            .header("X-User-ID", "flux-user").bodyValue(body).exchange().expectStatus().isForbidden().expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().returnResult().getResponseBody();

        @SuppressWarnings("unchecked")
        Map<String, Object> error = mapper.readValue(bytes, Map.class);
        assertThat(error.get("type")).isEqualTo("error");
        assertThat(error.get("code")).isEqualTo("ACCESS_DENIED");
        assertThat(error.get("resource")).isEqualTo("query");
        assertThat(error.get("action")).isEqualTo("execute");
        assertThat(error.get("reason")).isEqualTo("policy denied");
    }
}
