/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler;
import com.openjiuwen.service.spec.lifecycle.AgentShutdownHook;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;

/**
 * Collected and ordered lifecycle hooks.
 */
public final class AgentLifecycleHooks {

    private final List<AgentInitHook> initHooks;
    private final List<AgentShutdownHook> shutdownHooks;
    private final List<AgentInterruptHandler> interruptHandlers;

    public AgentLifecycleHooks(
            List<AgentInitHook> initHooks,
            List<AgentShutdownHook> shutdownHooks,
            List<AgentInterruptHandler> interruptHandlers) {
        this.initHooks = sorted(initHooks);
        this.shutdownHooks = sorted(shutdownHooks);
        this.interruptHandlers = sorted(interruptHandlers);
    }

    public List<AgentInitHook> initHooks() {
        return initHooks;
    }

    public List<AgentShutdownHook> shutdownHooks() {
        return shutdownHooks;
    }

    public List<AgentInterruptHandler> interruptHandlers() {
        return interruptHandlers;
    }

    private static <T> List<T> sorted(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        AnnotationAwareOrderComparator.sort(copy);
        return copy;
    }
}
