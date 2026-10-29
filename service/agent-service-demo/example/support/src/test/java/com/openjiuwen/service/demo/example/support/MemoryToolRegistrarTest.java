/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Unit tests for {@link MemoryToolRegistrar}.
 *
 * @since 0.1.0
 */
class MemoryToolRegistrarTest {
    @Test
    void addOptionsRouteToolWritesThroughDerivePath() {
        // 工具写入与 syncTurn 必须共享 infer=true：直写（infer=false）不抽取也不去重，
        // 矛盾事实会并存，旧事实仍可能排在 top1（issue #124）。
        Map<String, Object> options = MemoryToolRegistrar.addOptions();

        assertThat(options).containsEntry("infer", true);
    }
}
