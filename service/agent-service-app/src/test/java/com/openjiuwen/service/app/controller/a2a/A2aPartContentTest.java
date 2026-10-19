/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Verifies default and custom remote Task result selection.
 *
 * @since 0.1.4
 */
class A2aPartContentTest {
    @Test
    void absentHookPreservesArtifactSelectionAndStatusFallback() {
        Task answer = task("answer");
        Task blank = task("  ");

        assertThat(A2aPartContent.extractTaskResult(answer, "status", null)).isEqualTo("answer");
        assertThat(A2aPartContent.extractTaskResult(blank, "status", null)).isEqualTo("status");
    }

    @Test
    void customResultIsUsedExactlyIncludingEmptyAndWhitespace() {
        Task answer = task("default");
        for (String selected : List.of("", "  ", "custom")) {
            assertThat(A2aPartContent.extractTaskResult(answer, "status", current -> {
                assertThat(current).isSameAs(answer);
                return selected;
            })).isEqualTo(selected);
        }
    }

    @Test
    void nullAndExceptionsAreLocalHookErrorsWithoutDefaultFallback() {
        Task answer = task("default");
        assertThatThrownBy(() -> A2aPartContent.extractTaskResult(answer, "status", current -> null))
                .isInstanceOf(A2aPartContent.ResultHookException.class)
                .hasCauseInstanceOf(NullPointerException.class);
        IllegalArgumentException cause = new IllegalArgumentException("429 rate limit malformed protocol");
        assertThatThrownBy(() -> A2aPartContent.extractTaskResult(answer, "status", current -> {
            throw cause;
        })).isInstanceOf(A2aPartContent.ResultHookException.class)
                .hasMessage("Remote task result extraction failed").hasCause(cause);
    }

    private static Task task(String text) {
        return Task.builder().id("remote-task").contextId("remote-context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(Artifact.builder().artifactId("answer").parts(new TextPart(text)).build()))
                .build();
    }
}
