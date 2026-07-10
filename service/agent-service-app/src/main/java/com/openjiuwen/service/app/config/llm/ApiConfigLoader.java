/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Loads the agent-core compatible {@code apiconfig.json} format.
 *
 * @since 0.1.0
 */
final class ApiConfigLoader {
    static final String API_CONFIG_ENV = "OPENJIUWEN_API_CONFIG";

    static final String DEFAULT_FILE_NAME = "apiconfig.json";

    private static final String KEY_API_BASE = "API_BASE";

    private static final String KEY_API_KEY = "API_KEY";

    private static final String KEY_PROVIDER = "MODEL_PROVIDER";

    private static final String KEY_MODEL_NAME = "MODEL_NAME";

    private static final String KEY_SSL_VERIFY = "LLM_SSL_VERIFY";

    private static final long MAX_FILE_SIZE_BYTES = 1024L * 1024L;

    private static final int MAX_PARENT_LEVELS = 6;

    private final ObjectMapper objectMapper;

    private final Environment environment;

    private final Supplier<Path> workingDirectorySupplier;

    ApiConfigLoader(ObjectMapper objectMapper, Environment environment) {
        this(objectMapper, environment, () -> Path.of("").toAbsolutePath());
    }

    ApiConfigLoader(ObjectMapper objectMapper, Environment environment, Supplier<Path> workingDirectorySupplier) {
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.workingDirectorySupplier = workingDirectorySupplier;
    }

    Optional<ApiConfigValues> load(String explicitPath, boolean shouldAutoDiscover) {
        Optional<Path> path = resolvePath(explicitPath, shouldAutoDiscover);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(path.get()));
    }

    private Optional<Path> resolvePath(String explicitPath, boolean shouldAutoDiscover) {
        if (hasText(explicitPath)) {
            return Optional.of(requireConfigFile(explicitPath, "openjiuwen.service.llm.config-file"));
        }

        String environmentPath = environment.getProperty(API_CONFIG_ENV);
        if (hasText(environmentPath)) {
            return Optional.of(requireConfigFile(environmentPath, API_CONFIG_ENV));
        }

        if (!shouldAutoDiscover) {
            return Optional.empty();
        }

        Path directory = workingDirectorySupplier.get().toAbsolutePath().normalize();
        for (int level = 0; level <= MAX_PARENT_LEVELS && directory != null; level++) {
            Path candidate = directory.resolve(DEFAULT_FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    private static Path requireConfigFile(String configuredPath, String sourceName) {
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(sourceName + " does not reference a readable regular file: " + path);
        }
        return path;
    }

    private ApiConfigValues read(Path path) {
        try {
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                throw new IllegalStateException("LLM API configuration file exceeds 1 MiB: " + path);
            }
            try (InputStream input = Files.newInputStream(path)) {
                Map<String, Object> raw = objectMapper.readValue(input, new TypeReference<>() {});
                if (raw == null) {
                    throw new IllegalStateException("LLM API configuration file must contain a JSON object: " + path);
                }
                return new ApiConfigValues(readText(raw, KEY_PROVIDER), readText(raw, KEY_API_KEY),
                    readText(raw, KEY_API_BASE), readText(raw, KEY_MODEL_NAME), readBoolean(raw, KEY_SSL_VERIFY));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read LLM API configuration file: " + path, exception);
        }
    }

    private static Optional<String> readText(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String text) {
            return Optional.of(text);
        }
        throw new IllegalStateException(key + " in LLM API configuration file must be a string");
    }

    private static Optional<Boolean> readBoolean(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Boolean parsedBoolean) {
            return Optional.of(parsedBoolean);
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return Optional.of(Boolean.TRUE);
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return Optional.of(Boolean.FALSE);
            }
        }
        throw new IllegalStateException(key + " in LLM API configuration file must be true or false");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static final class ApiConfigValues {
        private final Optional<String> provider;

        private final Optional<String> apiKey;

        private final Optional<String> apiBase;

        private final Optional<String> modelName;

        private final Optional<Boolean> shouldVerifySsl;

        ApiConfigValues(Optional<String> provider, Optional<String> apiKey, Optional<String> apiBase,
            Optional<String> modelName, Optional<Boolean> shouldVerifySsl) {
            this.provider = provider;
            this.apiKey = apiKey;
            this.apiBase = apiBase;
            this.modelName = modelName;
            this.shouldVerifySsl = shouldVerifySsl;
        }

        static ApiConfigValues empty() {
            return new ApiConfigValues(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        }

        Optional<String> provider() {
            return provider;
        }

        Optional<String> apiKey() {
            return apiKey;
        }

        Optional<String> apiBase() {
            return apiBase;
        }

        Optional<String> modelName() {
            return modelName;
        }

        Optional<Boolean> shouldVerifySsl() {
            return shouldVerifySsl;
        }
    }
}
