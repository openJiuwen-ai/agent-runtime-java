/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.testreliability.llmconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.LlmProperties;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TC_L_002 — apiconfig.json 文件运行时被篡改后的 resolve() 稳定性验证。
 *
 * <p>测试场景：首次 resolve() 成功读取合法 apiconfig.json 并缓存结果 →
 * 运行期间文件被篡改为非法内容（空文件、非法 JSON、根节点为数组等）→
 * 再次 resolve() 返回缓存结果，不读取篡改后的文件内容。</p>
 *
 * <table>
 *   <tr><td><b>维度</b></td><td><b>内容</b></td></tr>
 *   <tr><td>场景</td><td>首次resolve成功缓存 → 文件篡改为非法内容 → 再次resolve返回缓存</td></tr>
 *   <tr><td>前置</td><td>首次resolve()已成功读取合法apiconfig.json并缓存结果</td></tr>
 *   <tr><td>步骤</td><td>首次resolve → 篡改文件 → 再次resolve → 验证缓存不受篡改影响</td></tr>
 *   <tr><td>预期</td><td>文件篡改后resolve()返回缓存结果；缓存对象与首次一致；不读取篡改内容</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
@Tag("system-test")
class LlmConfigTamperToleranceIT {
    /** 本测试的预期配置值集合。 */
    private static final ConfigExpectation EXPECTED_CONFIG = new ConfigExpectation(
        "OriginalProvider", "plain:original-key",
        "https://llm.internal/v1", "original-model", true);

    /** 解密器调用计数器，用于验证缓存机制是否避免重复解密。 */
    private AtomicInteger decryptorInvocations;

    private CredentialDecryptor decryptor;

    private LlmConfigResolver resolver;

    private Path configFile;

    private ResolvedLlmConfig initialResult;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        configFile = tempDir.resolve("apiconfig.json");
        Files.writeString(configFile, """
            {
              "API_BASE": "https://llm.internal/v1",
              "API_KEY": "ENC:original-key",
              "MODEL_PROVIDER": "OriginalProvider",
              "MODEL_NAME": "original-model",
              "LLM_SSL_VERIFY": true
            }
            """);

        decryptorInvocations = new AtomicInteger(0);
        decryptor = createTrackingDecryptor(decryptorInvocations);

        LlmProperties properties = new LlmProperties();
        properties.setConfigFile(configFile.toString());

        resolver = new LlmConfigResolver(properties, new MockEnvironment(), decryptor);

        // ── 步骤 1：完成首次 resolve() 并验证配置正确 ──
        initialResult = resolver.resolveRequired();
        verifyResolvedConfig(initialResult, EXPECTED_CONFIG);
        assertThat(decryptorInvocations.get()).isEqualTo(1);
    }

    // ── TC_L_002-1：文件被篡改为空文件 ──

    @Test
    void resolveReturnsCachedAfterFileTamperedToEmpty() throws Exception {
        Files.writeString(configFile, "");

        ResolvedLlmConfig afterTamper = resolver.resolveRequired();

        assertThat(afterTamper)
            .as("文件篡改为空后 resolve() 应返回缓存结果，不读取篡改内容")
            .isSameAs(initialResult);

        verifyResolvedConfig(afterTamper, EXPECTED_CONFIG);

        assertThat(decryptorInvocations.get())
            .as("文件篡改后 resolve() 不应再次调用解密器")
            .isEqualTo(1);
    }

    // ── TC_L_002-2：文件被篡改为非法 JSON ──

    @Test
    void resolveReturnsCachedAfterFileTamperedToMalformedJson() throws Exception {
        Files.writeString(configFile, "{not-valid-json!!!");

        ResolvedLlmConfig afterTamper = resolver.resolveRequired();

        assertThat(afterTamper)
            .as("文件篡改为非法 JSON 后 resolve() 应返回缓存结果")
            .isSameAs(initialResult);

        verifyResolvedConfig(afterTamper, EXPECTED_CONFIG);

        assertThat(decryptorInvocations.get())
            .as("文件篡改后解密器不应被再次调用")
            .isEqualTo(1);
    }

    // ── TC_L_002-3：文件被篡改为根节点为数组 ──

    @Test
    void resolveReturnsCachedAfterFileTamperedToArrayRoot() throws Exception {
        Files.writeString(configFile, "[\"tampered\", \"values\"]");

        ResolvedLlmConfig afterTamper = resolver.resolveRequired();

        assertThat(afterTamper)
            .as("文件篡改为数组根节点后 resolve() 应返回缓存结果")
            .isSameAs(initialResult);

        verifyResolvedConfig(afterTamper, EXPECTED_CONFIG);

        assertThat(decryptorInvocations.get()).isEqualTo(1);
    }

    // ── TC_L_002-4：文件被篡改为 null 根节点 ──

    @Test
    void resolveReturnsCachedAfterFileTamperedToNullRoot() throws Exception {
        Files.writeString(configFile, "null");

        ResolvedLlmConfig afterTamper = resolver.resolveRequired();

        assertThat(afterTamper)
            .as("文件篡改为 null 根节点后 resolve() 应返回缓存结果")
            .isSameAs(initialResult);

        verifyResolvedConfig(afterTamper, EXPECTED_CONFIG);

        assertThat(decryptorInvocations.get()).isEqualTo(1);
    }

    // ── TC_L_002-5：文件被篡改为不同的合法配置（值被替换） ──
    // 方法名缩短以符合 G.NAM.01（标识符不超过64字符）

    @Test
    void resolveReturnsCachedAfterTamperedToOtherValidCfg() throws Exception {
        Files.writeString(configFile, """
            {
              "API_BASE": "https://tampered.example/v1",
              "API_KEY": "tampered-key",
              "MODEL_PROVIDER": "TamperedProvider",
              "MODEL_NAME": "tampered-model",
              "LLM_SSL_VERIFY": false
            }
            """);

        ResolvedLlmConfig afterTamper = resolver.resolveRequired();

        assertThat(afterTamper)
            .as("文件篡改为不同合法配置后 resolve() 应返回原始缓存，而非篡改后的新配置")
            .isSameAs(initialResult);

        verifyResolvedConfig(afterTamper, EXPECTED_CONFIG);

        assertThat(decryptorInvocations.get())
            .as("文件篡改后解密器不应被再次调用")
            .isEqualTo(1);
    }

    // ── TC_L_002-6：连续多次篡改后 resolve() 均稳定返回缓存 ──

    @Test
    void resolveRemainsStableAcrossMultipleTamperAttempts() throws Exception {
        // 篡改 1：空文件
        Files.writeString(configFile, "");
        assertThat(resolver.resolveRequired()).isSameAs(initialResult);
        assertThat(resolver.resolveRequired().getProvider()).isEqualTo("OriginalProvider");

        // 篡改 2：非法 JSON
        Files.writeString(configFile, "{broken");
        assertThat(resolver.resolveRequired()).isSameAs(initialResult);

        // 篡改 3：数组根节点
        Files.writeString(configFile, "[1,2,3]");
        assertThat(resolver.resolveRequired()).isSameAs(initialResult);

        // 篡改 4：null 根节点
        Files.writeString(configFile, "null");
        assertThat(resolver.resolveRequired()).isSameAs(initialResult);

        // 篡改 5：不同合法配置
        Files.writeString(configFile, """
            {"MODEL_PROVIDER":"FakeProvider","API_KEY":"fake"}
            """);
        assertThat(resolver.resolveRequired()).isSameAs(initialResult);
        assertThat(resolver.resolveRequired().getProvider())
            .as("连续多次篡改后 provider 仍为原始缓存值，不受任何篡改影响")
            .isEqualTo("OriginalProvider");

        assertThat(decryptorInvocations.get())
            .as("连续多次篡改后解密器不应被再次调用")
            .isEqualTo(1);
    }

    // ── 辅助方法 ──

    /**
     * 创建带调用计数的 CredentialDecryptor，解密时将 "ENC:" 替换为 "plain:"。
     *
     * @param invocationCounter 解密调用计数器
     * @return 跟踪调用次数的 CredentialDecryptor 实例
     */
    private static CredentialDecryptor createTrackingDecryptor(AtomicInteger invocationCounter) {
        return new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                invocationCounter.incrementAndGet();
                return ciphertext.replace("ENC:", "plain:");
            }
        };
    }

    /**
     * 验证 ResolvedLlmConfig 的所有字段是否符合预期配置值。
     *
     * @param config 待验证的配置对象
     * @param expectation 预期配置值集合
     */
    private static void verifyResolvedConfig(ResolvedLlmConfig config,
        ConfigExpectation expectation) {
        assertThat(config.getProvider()).isEqualTo(expectation.provider);
        assertThat(config.getApiKey()).isEqualTo(expectation.apiKey);
        assertThat(config.getApiBase()).isEqualTo(expectation.apiBase);
        assertThat(config.getModelName()).isEqualTo(expectation.modelName);
        assertThat(config.isSslVerify()).isEqualTo(expectation.isSslVerify);
    }

    // ── 内部类：预期配置值封装 ──

    /**
     * 预期 ResolvedLlmConfig 字段值封装，避免 verifyResolvedConfig 方法参数超过5个。
     * 布尔字段使用 is 前缀命名（符合 G.NAM.08 规则）。
     */
    private static final class ConfigExpectation {
        final String provider;
        final String apiKey;
        final String apiBase;
        final String modelName;
        final boolean isSslVerify;

        ConfigExpectation(String provider, String apiKey, String apiBase,
            String modelName, boolean isSslVerify) {
            this.provider = provider;
            this.apiKey = apiKey;
            this.apiBase = apiBase;
            this.modelName = modelName;
            this.isSslVerify = isSslVerify;
        }
    }
}
