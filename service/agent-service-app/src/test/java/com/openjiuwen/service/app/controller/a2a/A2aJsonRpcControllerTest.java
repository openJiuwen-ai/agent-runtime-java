/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Tests A2A JSON-RPC request parsing at the protocol boundary.
 *
 * @since 0.1.0
 */
class A2aJsonRpcControllerTest {
    @Test
    void parsesParamsAndMessageMetadataAsDistinctMaps() {
        JsonObject request = requestWithMetadata("{\"request-scope\":\"params\"}",
                "{\"request-scope\":\"message\",\"trace-id\":\"trace-1\"}");

        MessageSendParams params = A2aJsonRpcController.parseParams(request);

        assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("request-scope", "params"));
        assertThat(params.message().metadata()).containsEntry("request-scope", "message").containsEntry("trace-id",
                "trace-1");
    }

    @Test
    void rejectsNonObjectMetadataAtEitherProtocolLevel() {
        assertThatThrownBy(() -> A2aJsonRpcController.parseParams(requestWithMetadata("[]", "{}")))
                .isInstanceOf(InvalidParamsError.class);
        assertThatThrownBy(() -> A2aJsonRpcController.parseParams(requestWithMetadata("{}", "[]")))
                .isInstanceOf(InvalidParamsError.class);
    }

    private static JsonObject requestWithMetadata(String paramsMetadata, String messageMetadata) {
        String json = "{\"params\":{\"metadata\":" + paramsMetadata + ",\"message\":{"
                + "\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\",\"contextId\":\"ctx-1\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}],\"metadata\":" + messageMetadata + "}}}";
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
