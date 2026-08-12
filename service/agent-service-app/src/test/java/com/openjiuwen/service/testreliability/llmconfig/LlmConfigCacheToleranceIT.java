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
 * TC_L_001 — apiconfig.json 文件运行时被删除后的 resolve() 容错验证。
 *
 * <p>测试场景：首次 resolve() 成功读取 apiconfig.json 并缓存结果 →
 * 运行期间文件被删除 → 再次 resolve() 返回缓存结果，不抛出文件不存在异常。</p>
 *
 * <table>
 *   <tr><td><b>维度</b></td><td><b>内容</b></td></tr>
 *   <tr><td>场景</td><td>首次resolve成功缓存 → 文件删除 → 再次resolve返回缓存</td></tr>
 *   <tr><td>前置</td><td>首次resolve()已成功读取apiconfig.json并缓存结果</td></tr>
 *   <tr><td>步骤</td><td>首次resolve → 删除文件 → 再次resolve → 验证缓存一致性</td></tr>
 *   <tr><td>预期</td><td>文件删除后resolve()返回缓存结果；缓存对象与首次一致；无文件不存在异常</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
@Tag("system-test")
class LlmConfigCacheToleranceIT {
    /** 本测试的预期配置值集合，用于 verifyResolvedConfig 对比。 */
    private static final ConfigExpectation EXPECTED_CONFIG = new ConfigExpectation(
        "CacheTestProvider", "plain:test-key",
        "https://llm.internal/v1", "cache-test-model", true);

    /** 解密器调用计数器，用于验证缓存机制是否避免重复解密。 */
    private AtomicInteger decryptorInvocations;

    private LlmConfigResolver resolver;

    private Path configFile;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        configFile = tempDir.resolve("apiconfig.json");
        Files.writeString(configFile, """
            {
              "API_BASE": "https://llm.internal/v1",
              "API_KEY": "ENC:test-key",
              "MODEL_PROVIDER": "CacheTestProvider",
              "MODEL_NAME": "cache-test-model",
              "LLM_SSL_VERIFY": true
            }
            """);

        decryptorInvocations = new AtomicInteger(0);
        CredentialDecryptor decryptor = createTrackingDecryptor(decryptorInvocations);

        LlmProperties properties = new LlmProperties();
        properties.setConfigFile(configFile.toString());

        // 使用公共构造函数（LlmProperties + Environment + CredentialDecryptor）
        // config-file 使用绝对路径，无需定制 workingDirectorySupplier
        resolver = new LlmConfigResolver(properties, new MockEnvironment(), decryptor);
    }

    // ── TC_L_001 主路径：首次 resolve → 删除文件 → 再次 resolve 返回缓存 ──

    @Test
    void resolveReturnsCachedResultAfterConfigFileDeleted() throws Exception {
        // ── 步骤 1：完成首次 resolve() 并验证配置正确 ──
        ResolvedLlmConfig firstResult = resolver.resolveRequired();
        verifyResolvedConfig(firstResult, EXPECTED_CONFIG);

        // 验证解密器只调用一次（缓存机制生效）
        assertThat(decryptorInvocations.get())
            .as("首次 resolve 应只调用解密器一次")
            .isEqualTo(1);

        // ── 步骤 2：删除 apiconfig.json 文件 ──
        Files.delete(configFile);
        assertThat(Files.exists(configFile))
            .as("apiconfig.json 文件应已被删除")
            .isFalse();

        // ── 步骤 3：再次调用 resolve()，验证返回缓存结果（不重新读取文件） ──
        ResolvedLlmConfig secondResult = resolver.resolveRequired();
        assertThat(secondResult)
            .as("文件删除后 resolve() 应返回缓存的配置结果，不抛出异常")
            .isNotNull();

        // ── 步骤 4：验证返回的配置对象与首次结果一致 ──
        assertThat(secondResult)
            .as("缓存结果应与首次结果为同一对象实例")
            .isSameAs(firstResult);

        verifyResolvedConfig(secondResult, EXPECTED_CONFIG);

        // 验证：解密器没有被再次调用（缓存机制确保不重复读取文件和解密）
        assertThat(decryptorInvocations.get())
            .as("文件删除后 resolve() 不应再次调用解密器（缓存生效）")
            .isEqualTo(1);
    }

    // ── TC_L_001 补充：多次 resolve 在文件删除后均返回缓存 ──

    @Test
    void repeatedResolveAfterFileDeletionAlwaysReturnsCachedResult() throws Exception {
        // 首次 resolve
        ResolvedLlmConfig firstResult = resolver.resolveRequired();
        assertThat(firstResult.getProvider()).isEqualTo("CacheTestProvider");
        assertThat(decryptorInvocations.get()).isEqualTo(1);

        // 删除文件
        Files.delete(configFile);

        // 连续 3 次 resolve，全部返回缓存结果
        for (int i = 0; i < 3; i++) {
            ResolvedLlmConfig cached = resolver.resolveRequired();
            assertThat(cached)
                .as("第 %d 次 resolve 应返回与首次相同的缓存实例", i + 1)
                .isSameAs(firstResult);
            assertThat(cached.getProvider())
                .as("第 %d 次缓存 provider 应一致", i + 1)
                .isEqualTo("CacheTestProvider");
            assertThat(cached.getApiKey())
                .as("第 %d 次缓存 api-key 应一致", i + 1)
                .isEqualTo("plain:test-key");
        }

        // 解密器总调用次数仍为 1（缓存保证不重复解密）
        assertThat(decryptorInvocations.get())
            .as("多次 resolve 不应重复调用解密器")
            .isEqualTo(1);
    }

    // ── TC_L_001 补充：resolve() 与 resolveRequired() 共享同一缓存 ──

    @Test
    void resolveAndResolveRequiredShareSameCacheAfterFileDeletion() throws Exception {
        // 通过 resolve() 首次解析
        ResolvedLlmConfig fromResolve = resolver.resolve();
        assertThat(fromResolve.getProvider()).isEqualTo("CacheTestProvider");

        // 删除文件
        Files.delete(configFile);

        // resolveRequired() 应返回同一缓存实例
        ResolvedLlmConfig fromResolveRequired = resolver.resolveRequired();
        assertThat(fromResolveRequired)
            .as("resolve() 和 resolveRequired() 应共享同一缓存实例")
            .isSameAs(fromResolve);

        // 再次 resolve() 也返回同一实例
        ResolvedLlmConfig fromResolveAgain = resolver.resolve();
        assertThat(fromResolveAgain)
            .as("多次 resolve() 应返回同一缓存实例")
            .isSameAs(fromResolve);
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

    // ── 内部类：预期配置值封装（解决 G.MET.01 参数>5 和 G.NAM.08 布尔命名问题） ──

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
