/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Thin ingress-only selection over the published catalog. Protocol controllers
 * determine the parameter source and project errors into their existing format.
 *
 * @since 0.1.2
 */
public final class HostedIngressResolver {
    /** Request attribute for optional ingress observers; never a business DTO field. */
    public static final String SELECTED_RUNTIME_ATTRIBUTE = HostedIngressResolver.class.getName() + ".selected";

    private static final String OBSERVERS_ATTRIBUTE = HostedIngressResolver.class.getName() + ".observers";

    private final HostedRuntimeCatalog catalog;

    public HostedIngressResolver(HostedRuntimeCatalog catalog) {
        this.catalog = catalog;
    }

    public HostedAgentRuntime resolveOrDefault(String agentId) {
        if (agentId != null && !HostedAgentDefinitions.isValidAgentId(agentId)) {
            throw new SelectionException(400, "HOSTED_AGENT_INVALID", "Invalid agent ID");
        }
        return catalog.resolve(agentId);
    }

    /**
     * Resolves GET trajectory parameters, rejecting duplicate occurrences.
     *
     * @param values all agentId query values, or null when absent
     * @return selected target
     */
    public HostedAgentRuntime resolveQuery(List<String> values) {
        if (values == null) {
            return resolveOrDefault(null);
        }
        if (values.size() != 1 || values.get(0) == null) {
            throw new SelectionException(400, "HOSTED_AGENT_INVALID", "Exactly one agentId value is required");
        }
        return resolveOrDefault(values.get(0));
    }

    /** Registers a request-local observer; observers never resolve or change the selected target. */
    public static void observeSelection(HttpServletRequest request, Consumer<HostedAgentRuntime> observer) {
        Object existing = request.getAttribute(OBSERVERS_ATTRIBUTE);
        SelectionObservers observers;
        if (existing instanceof SelectionObservers registered) {
            observers = registered;
        } else {
            observers = new SelectionObservers();
            request.setAttribute(OBSERVERS_ATTRIBUTE, observers);
        }
        observers.callbacks.add(observer);
    }

    /** Publishes the controller's validated selection before execution starts. */
    public static void selected(HttpServletRequest request, HostedAgentRuntime target) {
        Object previous = request.getAttribute(SELECTED_RUNTIME_ATTRIBUTE);
        if (previous == target) {
            return;
        }
        if (previous != null) {
            throw new IllegalStateException("Request target has already been selected");
        }
        request.setAttribute(SELECTED_RUNTIME_ATTRIBUTE, target);
        if (request.getAttribute(OBSERVERS_ATTRIBUTE) instanceof SelectionObservers observers) {
            observers.callbacks.forEach(observer -> observer.accept(target));
        }
    }

    private static final class SelectionObservers {
        private final List<Consumer<HostedAgentRuntime>> callbacks = new ArrayList<>();
    }

    /** Internal selection error, translated by REST or the SDK transport boundary. */
    public static final class SelectionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;

        private final String reason;

        public SelectionException(int status, String reason, String message) {
            super(message);
            this.status = status;
            this.reason = reason;
        }

        public int status() {
            return status;
        }

        public String reason() {
            return reason;
        }
    }
}
