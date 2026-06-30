/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ExampleApiConfigLoader {

    static final String DEFAULT_FILE_NAME = "apiconfig.json";
    static final String KEY_API_BASE = "API_BASE";
    static final String KEY_API_KEY = "API_KEY";
    static final String KEY_PROVIDER = "MODEL_PROVIDER";
    static final String KEY_MODEL_NAME = "MODEL_NAME";
    static final String KEY_SSL_VERIFY = "LLM_SSL_VERIFY";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PARENT_LEVELS = 6;

    private ExampleApiConfigLoader() {
    }

    static Optional<Map<String, String>> load(String explicitPath, boolean autoDiscover) {
        Optional<Path> resolved = resolvePath(explicitPath, autoDiscover);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Path path = resolved.get();
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> raw = MAPPER.readValue(in, new TypeReference<>() {
            });
            Map<String, String> config = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (value != null) {
                    config.put(key, String.valueOf(value));
                }
            });
            return Optional.of(config);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read model config file: " + path, ex);
        }
    }

    private static Optional<Path> resolvePath(String explicitPath, boolean autoDiscover) {
        List<Path> candidates = new ArrayList<>();
        if (hasText(explicitPath)) {
            candidates.add(Path.of(explicitPath));
        }
        String envPath = System.getenv("OPENJIUWEN_API_CONFIG");
        if (hasText(envPath)) {
            candidates.add(Path.of(envPath));
        }
        if (autoDiscover) {
            Path dir = Path.of("").toAbsolutePath();
            for (int level = 0; level <= MAX_PARENT_LEVELS && dir != null; level++) {
                candidates.add(dir.resolve(DEFAULT_FILE_NAME));
                dir = dir.getParent();
            }
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
