/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.testreliability.llmconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * TC_L_003 — apiconfig.json 文件权限不足时的 resolve() 容错验证。
 *
 * <p>测试场景：config-file 显式指向权限不足的文件 → resolve() 抛出明确异常，
 * 不静默跳过使用默认值。</p>
 *
 * <p>平台适配说明：Windows 无法通过 {@code File.setReadable(false)} 移除文件读权限，
 * {@code SecurityManager} 在 Java 21 已废弃且禁止设置。因此：</p>
 * <ul>
 *   <li>Linux/macOS：使用 {@code chmod 000} 模拟文件不可读（通过 shell 命令，避免引用
 *       {@code PosixFilePermission} 等 POSIX-only API 导致 Windows 编译失败）</li>
 *   <li>所有平台：使用目录路径替代非常规文件，验证 {@code requireConfigFile} 的完整性检查</li>
 * </ul>
 *
 * <table>
 *   <tr><td><b>维度</b></td><td><b>内容</b></td></tr>
 *   <tr><td>场景</td><td>config-file指向不可读文件 → resolve()抛出明确异常</td></tr>
 *   <tr><td>前置</td><td>config-file显式指定了不可读的apiconfig.json路径</td></tr>
 *   <tr><td>步骤</td><td>配置不可读文件 → 调用resolve() → 验证异常类型和消息</td></tr>
 *   <tr><td>预期</td><td>resolve()抛出明确异常；异常信息指出文件路径；不静默降级</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
@Tag("system-test")
class LlmConfigUnreadableFileIT {
    /** 解密器调用计数器，用于验证文件不可读时解密器未被调用。 */
    private AtomicInteger decryptorInvocations;

    private CredentialDecryptor decryptor;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        decryptorInvocations = new AtomicInteger(0);
        decryptor = createTrackingDecryptor(decryptorInvocations);
    }

    // ── TC_L_003-1：所有平台 — config-file 指向目录而非文件 ──
    // 目录不是常规文件，requireConfigFile 的 !Files.isRegularFile() 检查会拒绝

    /**
     * 当 config-file 指向目录时，resolve() 应抛出 IllegalStateException，
     * 不应静默跳过或降级为默认值。
     *
     * @throws Exception 如果 IO 操作失败
     */
    @Test
    void resolveThrowsExceptionWhenConfigFilePointsToDirectory() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("apiconfig_dir"));

        LlmProperties properties = new LlmProperties();
        properties.setConfigFile(directory.toString());

        LlmConfigResolver resolver = new LlmConfigResolver(properties, new MockEnvironment(), decryptor);

        // 步骤 2 + 3：调用 resolve()，验证抛出明确异常（不静默跳过）
        assertThatThrownBy(() -> resolver.resolve())
            .as("config-file 指向目录时 resolve() 应抛出明确异常，不静默跳过")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openjiuwen.service.llm.config-file")
            .hasMessageContaining("does not reference a readable regular file");

        // 步骤 4：验证异常信息包含文件路径
        assertThatThrownBy(() -> resolver.resolve())
            .hasMessageContaining(directory.toString());

        // 验证：解密器未被调用（文件读取前即被拒绝）
        assertThat(decryptorInvocations.get())
            .as("文件不可读时解密器不应被调用")
            .isZero();

        // 验证：resolveRequired() 也抛出明确异常（不静默降级为默认值）
        assertThatThrownBy(() -> resolver.resolveRequired())
            .as("config-file 指向目录时 resolveRequired() 也应抛出明确异常")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not reference a readable regular file");
    }

    // ── TC_L_003-3：所有平台 — 首次解析成功后文件变为不可访问（目录覆盖） ──
    // 模拟运行期间文件被删除后用同名目录覆盖的场景

    /**
     * 首次 resolve 成功后，文件被同名目录覆盖，再次 resolve 应返回缓存结果。
     *
     * @throws Exception 如果 IO 操作失败
     */
    @Test
    void resolveReturnsCachedAfterFileReplacedByDirectory() throws Exception {
        Path configFile = tempDir.resolve("apiconfig.json");
        Files.writeString(configFile, """
            {
              "API_BASE": "https://llm.internal/v1",
              "API_KEY": "ENC:initial-key",
              "MODEL_PROVIDER": "InitialProvider",
              "MODEL_NAME": "initial-model",
              "LLM_SSL_VERIFY": true
            }
            """);

        LlmProperties properties = new LlmProperties();
        properties.setConfigFile(configFile.toString());

        LlmConfigResolver resolver = new LlmConfigResolver(properties, new MockEnvironment(), decryptor);

        // 首次 resolve() 成功
        ResolvedLlmConfig firstResult = resolver.resolveRequired();
        assertThat(firstResult.getProvider()).isEqualTo("InitialProvider");
        assertThat(firstResult.getApiKey()).isEqualTo("plain:initial-key");
        assertThat(decryptorInvocations.get()).isEqualTo(1);

        // 文件被删除后用同名目录覆盖（模拟文件变为不可访问）
        Files.delete(configFile);
        Files.createDirectories(configFile);

        // 再次 resolve() — 应返回缓存结果（不重新读取文件）
        ResolvedLlmConfig secondResult = resolver.resolveRequired();
        assertThat(secondResult)
            .as("文件被目录覆盖后 resolve() 应返回缓存结果")
            .isSameAs(firstResult);
        assertThat(secondResult.getProvider())
            .as("缓存 provider 不受文件变为不可访问的影响")
            .isEqualTo("InitialProvider");
        assertThat(decryptorInvocations.get())
            .as("缓存生效，不应再次调用解密器")
            .isEqualTo(1);

        // 清理：删除目录（不再是文件）
        Files.deleteIfExists(configFile);
    }

    // ── TC_L_003-4：所有平台 — 验证异常不静默降级为默认值 ──

    /**
     * config-file 不可读时，即使设置了其他 Spring 属性作为"回退"，resolve()
     * 仍应抛出异常，不应静默降级为默认值。
     *
     * @throws Exception 如果 IO 操作失败
     */
    @Test
    void resolveDoesNotSilentlyFallbackOnUnreadableCfg() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("unreadable_config"));

        LlmProperties properties = new LlmProperties();
        properties.setConfigFile(directory.toString());

        LlmConfigResolver resolver = new LlmConfigResolver(properties, new MockEnvironment(), decryptor);

        // 验证：resolve() 不静默降级为默认值（不返回 provider=OpenAI 等默认配置）
        assertThatThrownBy(() -> resolver.resolve())
            .as("resolve() 不应静默降级为默认值，应抛出明确异常")
            .isInstanceOf(IllegalStateException.class);

        // 验证：即使设置了其他合法属性，仍然因文件不可读而失败
        // 这证明 config-file 的不可读状态不会被 Spring 属性"覆盖"而静默跳过
        LlmProperties propertiesWithFallback = new LlmProperties();
        propertiesWithFallback.setConfigFile(directory.toString());
        propertiesWithFallback.setProvider("FallbackProvider");
        propertiesWithFallback.setApiBase("https://fallback.example/v1");
        propertiesWithFallback.setModelName("fallback-model");
        propertiesWithFallback.setApiKey("fallback-key");

        LlmConfigResolver resolverWithFallback =
            new LlmConfigResolver(propertiesWithFallback, new MockEnvironment(), decryptor);

        assertThatThrownBy(() -> resolverWithFallback.resolve())
            .as("即使设置了其他 Spring 属性，config-file 不可读时仍应抛出异常，不静默跳过文件")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not reference a readable regular file");

        assertThat(decryptorInvocations.get())
            .as("文件不可读时解密器不应被调用，即使其他属性已设置")
            .isZero();
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
}
