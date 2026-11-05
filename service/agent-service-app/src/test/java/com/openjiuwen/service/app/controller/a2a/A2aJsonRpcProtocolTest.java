/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Unit tests for the JSON-RPC error-envelope post-processing in
 * {@link A2aJsonRpcProtocol} — the {@code error.details} to
 * {@code error.data} relocation for businessCode-carrying details
 * (DFX-006 §4.6).
 */
class A2aJsonRpcProtocolTest {
    @Test
    void errorResponseRelocatesBusinessCodeDetailsToDataSlot() {
        A2AError error = new A2AError(A2AErrorCodes.INTERNAL.code(),
                A2AAgentExecutor.ADMISSION_REJECTED_MESSAGE, Map.of("businessCode", "CONCURRENCY_LIMIT_REACHED"));

        ResponseEntity<String> response = A2aJsonRpcProtocol.errorResponse(null, error, HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertThat(body.has("id")).isTrue();
        assertThat(body.get("id").isJsonNull()).isTrue();
        JsonObject errorObject = body.getAsJsonObject("error");
        assertThat(errorObject.get("message").getAsString()).isEqualTo("Agent Runtime is temporarily unavailable");
        assertThat(errorObject.getAsJsonObject("data").get("businessCode").getAsString())
                .isEqualTo("CONCURRENCY_LIMIT_REACHED");
        assertThat(errorObject.has("details")).isFalse();
    }

    @Test
    void errorResponseKeepsDetailsUntouchedWithoutBusinessCode() {
        A2AError error = new A2AError(A2AErrorCodes.INTERNAL.code(), "boom", Map.of("foo", "bar"));

        ResponseEntity<String> response = A2aJsonRpcProtocol.errorResponse("req-1", error, HttpStatus.OK);

        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        JsonObject errorObject = body.getAsJsonObject("error");
        assertThat(errorObject.getAsJsonObject("details").get("foo").getAsString()).isEqualTo("bar");
        assertThat(errorObject.has("data")).isFalse();
    }

    @Test
    void errorResponseWithoutDetailsCarriesNoDataSlot() {
        A2AError error = new A2AError(A2AErrorCodes.INTERNAL.code(), "boom", null);

        ResponseEntity<String> response = A2aJsonRpcProtocol.errorResponse("req-1", error, HttpStatus.OK);

        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        JsonObject errorObject = body.getAsJsonObject("error");
        assertThat(errorObject.has("details")).isFalse();
        assertThat(errorObject.has("data")).isFalse();
    }
}
