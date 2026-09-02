/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.JsonParser;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

/**
 * Tests A2A SendMessage params Part parsing for the FEAT-036 multi-Part wire
 * format (text/raw/url/data flat discriminators).
 *
 * @since 0.1.0
 */
class A2aJsonRpcParamsParserTest {
    @Test
    void urlPartBecomesFileWithUriPreservingFilenameAndMediaType() {
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"url":"https://example.com/report.pdf","filename":"report.pdf","mediaType":"application/pdf"}
        ]}}}
        """);

        assertThat(params.message().parts()).singleElement().isInstanceOfSatisfying(FilePart.class, part -> {
            assertThat(part.file()).isInstanceOf(FileWithUri.class);
            FileWithUri uri = assertInstanceOf(FileWithUri.class, part.file());
            assertThat(uri.uri()).isEqualTo("https://example.com/report.pdf");
            assertThat(uri.name()).isEqualTo("report.pdf");
            assertThat(uri.mimeType()).isEqualTo("application/pdf");
        });
    }

    @Test
    void rawPartBecomesFileWithBytesPreservingBase64() {
        String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"raw":"%s","filename":"a.bin","mediaType":"application/octet-stream"}
        ]}}}
        """.formatted(base64));

        assertThat(params.message().parts()).singleElement().isInstanceOfSatisfying(FilePart.class, part -> {
            assertThat(part.file()).isInstanceOf(FileWithBytes.class);
            FileWithBytes bytes = assertInstanceOf(FileWithBytes.class, part.file());
            assertThat(bytes.bytes()).isEqualTo(base64);
            assertThat(bytes.name()).isEqualTo("a.bin");
            assertThat(bytes.mimeType()).isEqualTo("application/octet-stream");
        });
    }

    @Test
    void multipleRawPartsKeepOrder() {
        String first = Base64.getEncoder().encodeToString(new byte[] {1});
        String second = Base64.getEncoder().encodeToString(new byte[] {2});
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"raw":"%s","filename":"one.bin"},
          {"raw":"%s","filename":"two.bin"}
        ]}}}
        """.formatted(first, second));

        List<Part<?>> parts = params.message().parts();
        assertThat(parts).hasSize(2);
        assertThat(((FilePart) parts.get(0)).file()).isInstanceOf(FileWithBytes.class);
        assertThat(((FilePart) parts.get(1)).file()).isInstanceOf(FileWithBytes.class);
    }

    @Test
    void dataPartKeepsJsonType() {
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"data":{"reportId":42,"flag":true}}
        ]}}}
        """);

        assertThat(params.message().parts()).singleElement().isInstanceOfSatisfying(DataPart.class, part -> {
            assertThat(part.data()).isInstanceOf(java.util.Map.class);
            assertThat(((java.util.Map<?, ?>) part.data()).get("reportId")).isEqualTo(42L);
            assertThat(((java.util.Map<?, ?>) part.data()).get("flag")).isEqualTo(true);
        });
    }

    @Test
    void mixedPartsPreserveOrderAndTextSemantics() {
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"text":"analyze this"},
          {"url":"https://example.com/report.pdf","filename":"report.pdf"},
          {"data":{"key":"value"}}
        ]}}}
        """);

        List<Part<?>> parts = params.message().parts();
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).isInstanceOf(TextPart.class);
        assertThat(parts.get(1)).isInstanceOf(FilePart.class);
        assertThat(parts.get(2)).isInstanceOf(DataPart.class);
    }

    @Test
    void pureFileRequestWithoutTextPartIsValid() {
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"url":"https://example.com/report.pdf"}
        ]}}}
        """);

        assertThat(params.message().parts()).hasSize(1);
        assertThat(params.message().parts().get(0)).isInstanceOf(FilePart.class);
    }

    @Test
    void partMetadataIsPreservedOnFileAndDataParts() {
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"url":"https://example.com/report.pdf","metadata":{"trace":"t-1"}},
          {"data":{"k":"v"},"metadata":{"trace":"t-2"}}
        ]}}}
        """);

        List<Part<?>> parts = params.message().parts();
        assertThat(((FilePart) parts.get(0)).metadata()).containsEntry("trace", "t-1");
        assertThat(((DataPart) parts.get(1)).metadata()).containsEntry("trace", "t-2");
    }

    @Test
    void blankTextPartsAreSkippedAndAllBlankRequestIsRejected() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"text":"   "}
        ]}}}
        """)).isInstanceOf(InvalidParamsError.class);
    }

    @Test
    void rejectsPartWithMultipleContentFields() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"text":"hi","raw":"AAAA"}
        ]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("exactly one of text/raw/url/data");
    }

    @Test
    void rejectsPartWithoutContentField() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"filename":"orphan.pdf"}
        ]}}}
        """)).isInstanceOf(InvalidParamsError.class);
    }

    @Test
    void rejectsInvalidBase64Raw() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"raw":"not*base64!"}
        ]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("base64");
    }

    @Test
    void rejectsNonHttpUrlScheme() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"url":"ftp://example.com/report.pdf"}
        ]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("http or https scheme");
    }

    @Test
    void rejectsTooManyParts() {
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                parts.append(',');
            }
            parts.append("{\"text\":\"p").append(i).append("\"}");
        }
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[%s]}}}
        """.formatted(parts))).isInstanceOf(InvalidParamsError.class).hasMessageContaining("max-parts");
    }

    @Test
    void rejectsTextPartOverOneMegabyte() {
        String bigText = "a".repeat(1024 * 1024 + 1);
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"text":"%s"}
        ]}}}
        """.formatted(bigText))).isInstanceOf(InvalidParamsError.class).hasMessageContaining("max-text-data-bytes");
    }

    @Test
    void rejectsDataPartOverOneMegabyte() {
        String bigValue = "a".repeat(1024 * 1024 + 1);
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"data":{"blob":"%s"}}
        ]}}}
        """.formatted(bigValue))).isInstanceOf(InvalidParamsError.class).hasMessageContaining("max-text-data-bytes");
    }

    @Test
    void rejectsEmptyPartsArray() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("non-empty array");
    }

    @Test
    void rejectsNonStringTextField() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[{"text":42}]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("text must be a string");
    }

    @Test
    void rejectsNonStringRawField() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[{"raw":123}]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("raw must be a string");
    }

    @Test
    void rejectsNonStringUrlField() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[{"url":true}]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("url must be a string");
    }

    @Test
    void rejectsNonStringMediaType() {
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"url":"https://example.com/a.png","mediaType":42}
        ]}}}
        """)).isInstanceOf(InvalidParamsError.class).hasMessageContaining("mediaType must be a string");
    }

    @Test
    void mediaTypeIsPreservedVerbatimForNonStandardValues() {
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"url":"https://example.com/a.dat","mediaType":"vendor-specific-not-a-mime"}
        ]}}}
        """);

        assertThat(params.message().parts()).singleElement().isInstanceOfSatisfying(FilePart.class, part -> {
            assertThat(assertInstanceOf(FileWithUri.class, part.file()).mimeType()).isEqualTo("vendor-specific-not-a-mime");
        });
    }

    @Test
    void rejectsRawPartOverTenMegabyteLimit() {
        String overLimit = Base64.getEncoder().encodeToString(new byte[(int) (10L * 1024 * 1024 + 1)]);
        assertThatThrownBy(() -> parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"raw":"%s"}
        ]}}}
        """.formatted(overLimit))).isInstanceOf(InvalidParamsError.class).hasMessageContaining("max-raw-bytes");
    }

    @Test
    void acceptsRawPartAtExactTenMegabyteLimit() {
        String atLimit = Base64.getEncoder().encodeToString(new byte[(int) (10L * 1024 * 1024)]);
        MessageSendParams params = parse("""
        {"params":{"message":{"role":"ROLE_USER","parts":[
          {"raw":"%s"}
        ]}}}
        """.formatted(atLimit));

        assertThat(params.message().parts()).singleElement().isInstanceOf(FilePart.class);
    }

    private static MessageSendParams parse(String json) {
        return A2aJsonRpcParamsParser.parseMessageSendParams(JsonParser.parseString(json).getAsJsonObject());
    }
}
