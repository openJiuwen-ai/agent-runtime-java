/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests sandbox examples expose the Core standard sandbox API boundary.
 *
 * @since 2026-06-29
 */
class SandboxExampleProtocolBoundaryTest {
    @Test
    void sandboxExampleDocumentsCoreStandardSandboxApiOnly() throws IOException {
        String readme = Files.readString(Path.of("example/sandbox/README.md"), StandardCharsets.UTF_8);

        assertThat(readme).contains("/api/v1/sandboxes");
        assertThat(readme).doesNotContain("/invoke", "invoke_path", "--invoke-path", "MockSandboxServerExample");
    }

    @Test
    void sandboxExampleSourceDoesNotExposeTemporaryInvokePathOption() throws IOException {
        String source = Files.readString(Path.of("example/sandbox/SandboxAdapterExample.java"), StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("invoke_path", "--invoke-path");
        assertThat(Path.of("example/sandbox/MockSandboxServerExample.java")).doesNotExist();
    }
}
