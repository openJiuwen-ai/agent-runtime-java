/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

/**
 * Verifies registration validation and default-target selection.
 *
 * @since 0.1.2
 */
class HostedAgentDefinitionsTest {
    @Test
    void preservesOrderIdentityAndExplicitDefaultWithoutStartingHandlers() {
        TestHandler first = new TestHandler();
        TestHandler second = new TestHandler();
        var definitions = HostedAgentDefinitions.builder().add("first", first).add("Second_2", second)
                .defaultAgent("Second_2").build();
        assertThat(definitions.defaultAgentId()).isEqualTo("Second_2");
        assertThat(definitions.entries()).extracting(HostedAgentDefinitions.Entry::agentId)
                .containsExactly("first", "Second_2");
        assertThat(definitions.entries().get(0).handler()).isSameAs(first);
        assertThat(first.starts).isZero();
        assertThat(second.starts).isZero();
    }

    @Test
    void defaultsToFirstAndFreezesIndependentOfBuilder() {
        var builder = HostedAgentDefinitions.builder().add("a", new TestHandler());
        var first = builder.build();
        builder.add("b", new TestHandler()).defaultAgent("b");
        assertThat(first.defaultAgentId()).isEqualTo("a");
        assertThat(first.entries()).hasSize(1);
        assertThatThrownBy(() -> first.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(builder.build().defaultAgentId()).isEqualTo("b");
    }

    @Test
    void rejectsInvalidDeclarationsWithoutOverwritingEarlierOnes() {
        var handler = new TestHandler();
        var builder = HostedAgentDefinitions.builder().add("a", handler);
        assertThatThrownBy(() -> builder.add("a", new TestHandler())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.add("b", handler)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.add("bad/id", new TestHandler()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.add("nil", null)).isInstanceOf(NullPointerException.class);
        assertThat(builder.build().entries()).hasSize(1);
        assertThatThrownBy(() -> builder.defaultAgent("missing").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostedAgentDefinitions.builder().build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checksObjectIdentityRatherThanUserEquals() {
        var first = new TestHandler();
        var second = new TestHandler();
        assertThat(first).isEqualTo(second);
        assertThat(HostedAgentDefinitions.builder().add("a", first).add("b", second).build().entries()).hasSize(2);
    }

    private static final class TestHandler implements AgentHandler {
        private int starts;

        @Override
        public void start() {
            starts++;
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse();
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onComplete();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestHandler;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
