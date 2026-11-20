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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * Unit tests for the JSON-RPC error-envelope post-processing in
 * {@link A2aJsonRpcProtocol} — the {@code error.details} to
 * {@code error.data} relocation for businessCode-carrying details
 * (DFX-006 §4.6) — plus the request-id echo contract: JSON-RPC 2.0 requires
 * the response {@code id} to equal the request {@code id}, so numeric ids
 * must keep their exact value and integer shape (no Double rounding, no
 * {@code .0} suffix, no scientific notation), and an explicit JSON null id
 * must be accepted and echoed per JSON-RPC 2.0.
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

    @Test
    void parseRequestKeepsSmallNumericIdAsExactInteger() {
        A2aJsonRpcProtocol.Request request = A2aJsonRpcProtocol
                .parseRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{}}");

        assertThat(request.id()).isEqualTo(BigInteger.ONE);
        assertThat(A2aJsonRpcProtocol.resultEnvelope(request.id(), new JsonObject())).contains("\"id\":1,");
    }

    @Test
    void parseRequestPreservesIdsBeyondDoublePrecision() {
        A2aJsonRpcProtocol.Request request = A2aJsonRpcProtocol
                .parseRequest("{\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"method\":\"SendMessage\",\"params\":{}}");

        assertThat(request.id()).isEqualTo(new BigInteger("9007199254740993"));
        assertThat(A2aJsonRpcProtocol.resultEnvelope(request.id(), new JsonObject()))
                .contains("\"id\":9007199254740993,");
    }

    @Test
    void parseRequestPreservesLongMaxIdWithoutScientificNotation() {
        A2aJsonRpcProtocol.Request request = A2aJsonRpcProtocol.parseRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":9223372036854775807," + "\"method\":\"SendMessage\",\"params\":{}}");

        assertThat(request.id()).isEqualTo(new BigInteger("9223372036854775807"));
        assertThat(A2aJsonRpcProtocol.resultEnvelope(request.id(), new JsonObject()))
                .contains("\"id\":9223372036854775807,");
    }

    @Test
    void parseRequestKeepsStringIdUnchanged() {
        A2aJsonRpcProtocol.Request request = A2aJsonRpcProtocol.parseRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":\"my-string-id-1\",\"method\":\"SendMessage\",\"params\":{}}");

        assertThat(request.id()).isEqualTo("my-string-id-1");
        assertThat(A2aJsonRpcProtocol.resultEnvelope(request.id(), new JsonObject()))
                .contains("\"id\":\"my-string-id-1\",");
    }

    @Test
    void parseRequestParsesFractionalIdAsDecimal() {
        A2aJsonRpcProtocol.Request request = A2aJsonRpcProtocol
                .parseRequest("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"SendMessage\",\"params\":{}}");

        assertThat(request.id()).isEqualTo(new BigDecimal("1.5"));
        assertThat(A2aJsonRpcProtocol.resultEnvelope(request.id(), new JsonObject())).contains("\"id\":1.5,");
    }

    @Test
    void parseRequestAcceptsExplicitNullId() {
        A2aJsonRpcProtocol.Request request = A2aJsonRpcProtocol.parseRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"SendMessage\",\"params\":{}}");

        assertThat(request.id()).isNull();
        assertThat(A2aJsonRpcProtocol.resultEnvelope(request.id(), new JsonObject()))
                .contains("\"id\":null,");
    }

    @Test
    void invalidRequestWithExplicitNullIdEchoesNullId() {
        try {
            A2aJsonRpcProtocol.parseRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"SendMessage\",\"params\":\"bad\"}");
            throw new AssertionError("expected RequestException");
        } catch (A2aJsonRpcProtocol.RequestException exception) {
            assertThat(exception.getRequestId()).isNull();
            ResponseEntity<String> response = A2aJsonRpcProtocol.errorResponse(exception.getRequestId(),
                    exception.getError());
            JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
            assertThat(body.get("id").isJsonNull()).isTrue();
        }
    }

    @Test
    void parseRequestNormalizesExponentIntegerIdToExactInteger() {
        A2aJsonRpcProtocol.Request request = A2aJsonRpcProtocol
                .parseRequest("{\"jsonrpc\":\"2.0\",\"id\":1e3,\"method\":\"SendMessage\",\"params\":{}}");

        assertThat(request.id()).isEqualTo(new BigInteger("1000"));
        assertThat(A2aJsonRpcProtocol.resultEnvelope(request.id(), new JsonObject()))
                .contains("\"id\":1000,");
    }

    @Test
    void invalidRequestEchoesNumericIdAsInteger() {
        try {
            A2aJsonRpcProtocol.parseRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"method\":\"SendMessage\",\"params\":\"bad\"}");
            throw new AssertionError("expected RequestException");
        } catch (A2aJsonRpcProtocol.RequestException exception) {
            assertThat(exception.getRequestId()).isEqualTo(new BigInteger("9007199254740993"));
            ResponseEntity<String> response = A2aJsonRpcProtocol.errorResponse(exception.getRequestId(),
                    exception.getError());
            JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
            assertThat(body.get("id").getAsString()).isEqualTo("9007199254740993");
        }
    }
}
