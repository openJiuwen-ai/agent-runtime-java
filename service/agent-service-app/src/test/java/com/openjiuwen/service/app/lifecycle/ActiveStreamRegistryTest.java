/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveStreamRegistryTest {

    @Test
    void cancelMarksHandleAndDrainsRegistry() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamCancellationHandle first = registry.register("c1");
        StreamCancellationHandle second = registry.register("c1");

        assertThat(registry.activeCount()).isEqualTo(2);

        registry.cancel("c1");

        assertThat(first.isCancelled()).isTrue();
        assertThat(second.isCancelled()).isTrue();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void unregisterRemovesHandleWithoutCancellingOthers() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamCancellationHandle keep = registry.register("c1");
        StreamCancellationHandle remove = registry.register("c1");

        registry.unregister("c1", remove);

        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(keep.isCancelled()).isFalse();
        assertThat(remove.isCancelled()).isFalse();
    }

    @Test
    void awaitDrainCompletesWhenStreamsFinish() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamCancellationHandle handle = registry.register("c1");
        registry.unregister("c1", handle);

        assertThat(registry.awaitDrain(1000L)).isTrue();
    }
}
