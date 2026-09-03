/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.part;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests the protocol-level hygiene rules for normalized A2A parts:
 * mutual exclusion, base64 validity,
 * raw/parts/text-data size boundaries, url scheme whitelist,
 * filename/metadata length hygiene.
 *
 * @since 0.1.0
 */
class A2aPartRulesTest {
    private static final long RAW_LIMIT = 16L;

    private static final long TEXT_DATA_LIMIT = 16L;

    private static final int PARTS_LIMIT = 2;

    @Test
    void acceptsValidTextPart() {
        assertThat(A2aPartRules.validate(List.of(part("text", "hello")), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT))
                .isEmpty();
    }

    @Test
    void acceptsValidRawPart() {
        Map<String, Object> raw = part("raw", null);
        raw.put("bytesBase64", Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)));
        raw.put("byteSize", 5L);
        raw.put("filename", "report.pdf");
        raw.put("mediaType", "application/pdf");
        assertThat(A2aPartRules.validate(List.of(raw), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT)).isEmpty();
    }

    @Test
    void acceptsValidUrlPartForHttpAndHttps() {
        Map<String, Object> http = part("url", null);
        http.put("url", "http://files.example.com/report.pdf");
        Map<String, Object> https = part("url", null);
        https.put("url", "https://files.example.com/report.pdf");
        assertThat(A2aPartRules.validate(List.of(http), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT)).isEmpty();
        assertThat(A2aPartRules.validate(List.of(https), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT)).isEmpty();
    }

    @Test
    void acceptsValidDataPart() {
        Map<String, Object> data = part("data", null);
        data.put("data", Map.of("risk", "low"));
        assertThat(A2aPartRules.validate(List.of(data), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT)).isEmpty();
    }

    @Test
    void rejectsUnknownKind() {
        Optional<String> violation = A2aPartRules.validate(List.of(part("file", null)), RAW_LIMIT, TEXT_DATA_LIMIT,
                PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("kind");
    }

    @Test
    void rejectsMissingKind() {
        Map<String, Object> noKind = new LinkedHashMap<>();
        noKind.put("text", "hello");
        Optional<String> violation = A2aPartRules.validate(List.of(noKind), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("kind");
    }

    @Test
    void rejectsDiscriminatorMismatchWithKind() {
        Map<String, Object> mixed = part("text", "hello");
        mixed.put("url", "https://files.example.com/report.pdf");
        Optional<String> violation = A2aPartRules.validate(List.of(mixed), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("exactly one");
    }

    @Test
    void rejectsKindWithoutPayload() {
        Optional<String> violation = A2aPartRules.validate(List.of(part("raw", null)), RAW_LIMIT, TEXT_DATA_LIMIT,
                PARTS_LIMIT);
        assertThat(violation).isPresent();
    }

    @Test
    void rejectsInvalidBase64() {
        Map<String, Object> raw = part("raw", null);
        raw.put("bytesBase64", "!!!not-base64!!!");
        Optional<String> violation = A2aPartRules.validate(List.of(raw), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("not valid base64");
    }

    @Test
    void rejectsRawExceedingMaxRawBytes() {
        Map<String, Object> raw = part("raw", null);
        raw.put("bytesBase64", Base64.getEncoder().encodeToString(new byte[(int) RAW_LIMIT + 1]));
        Optional<String> violation = A2aPartRules.validate(List.of(raw), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("exceeds max-raw-bytes " + RAW_LIMIT);
    }

    @Test
    void acceptsRawAtExactLimit() {
        Map<String, Object> raw = part("raw", null);
        raw.put("bytesBase64", Base64.getEncoder().encodeToString(new byte[(int) RAW_LIMIT]));
        assertThat(A2aPartRules.validate(List.of(raw), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT)).isEmpty();
    }

    @Test
    void rejectsTooManyParts() {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 0; i <= PARTS_LIMIT; i++) {
            parts.add(part("text", "part-" + i));
        }
        Optional<String> violation = A2aPartRules.validate(parts, RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("exceeds max-parts " + PARTS_LIMIT);
    }

    @Test
    void rejectsTextExceedingMaxTextDataBytes() {
        Optional<String> violation = A2aPartRules.validate(List.of(part("text", "a".repeat((int) TEXT_DATA_LIMIT + 1))),
                RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("exceeds max-text-data-bytes " + TEXT_DATA_LIMIT);
    }

    @Test
    void acceptsTextAtExactLimit() {
        assertThat(A2aPartRules.validate(List.of(part("text", "a".repeat((int) TEXT_DATA_LIMIT))), RAW_LIMIT,
                TEXT_DATA_LIMIT, PARTS_LIMIT)).isEmpty();
    }

    @Test
    void rejectsDataExceedingMaxTextDataBytes() {
        Map<String, Object> data = part("data", null);
        data.put("data", "b".repeat((int) TEXT_DATA_LIMIT + 1));
        Optional<String> violation = A2aPartRules.validate(List.of(data), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("exceeds max-text-data-bytes " + TEXT_DATA_LIMIT);
    }

    @Test
    void rejectsBlankUrl() {
        for (String blank : new String[] {"", "   "}) {
            Map<String, Object> url = part("url", null);
            url.put("url", blank);
            Optional<String> violation = A2aPartRules.validate(List.of(url), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
            assertThat(violation).isPresent();
            assertThat(violation.get()).contains("non-blank");
        }
    }

    @Test
    void rejectsUrlWithNonHttpScheme() {
        Map<String, Object> url = part("url", null);
        url.put("url", "ftp://files.example.com/report.pdf");
        Optional<String> violation = A2aPartRules.validate(List.of(url), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("must use http or https scheme");
    }

    @Test
    void rejectsFilenameLongerThan255Chars() {
        Map<String, Object> raw = part("raw", null);
        raw.put("bytesBase64", Base64.getEncoder().encodeToString(new byte[1]));
        raw.put("filename", "f".repeat(256));
        Optional<String> violation = A2aPartRules.validate(List.of(raw), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("filename");
    }

    @Test
    void acceptsFilenameAt255Chars() {
        Map<String, Object> raw = part("raw", null);
        raw.put("bytesBase64", Base64.getEncoder().encodeToString(new byte[1]));
        raw.put("filename", "f".repeat(255));
        assertThat(A2aPartRules.validate(List.of(raw), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT)).isEmpty();
    }

    @Test
    void rejectsOversizedMetadata() {
        Map<String, Object> text = part("text", "hello");
        text.put("metadata", Map.of("blob", "m".repeat(20 * 1024)));
        Optional<String> violation = A2aPartRules.validate(List.of(text), RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("metadata");
    }

    @Test
    void reportsFirstViolationOnly() {
        List<Map<String, Object>> parts = List.of(part("text", "ok"), part("file", null));
        Optional<String> violation = A2aPartRules.validate(parts, RAW_LIMIT, TEXT_DATA_LIMIT, PARTS_LIMIT);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("parts[1]");
    }

    @Test
    void defaultLimitsMatchDesignValues() {
        assertThat(A2aPartLimits.DEFAULT_MAX_RAW_BYTES).isEqualTo(10L * 1024 * 1024);
        assertThat(A2aPartLimits.DEFAULT_MAX_PARTS).isEqualTo(100);
        assertThat(A2aPartLimits.DEFAULT_MAX_REQUEST_BODY_BYTES).isEqualTo(100L * 1024 * 1024);
        assertThat(A2aPartLimits.DEFAULT_MAX_TEXT_DATA_BYTES).isEqualTo(1024L * 1024);
    }

    private static Map<String, Object> part(String kind, String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("kind", kind);
        if (text != null) {
            part.put("text", text);
        }
        return part;
    }
}
