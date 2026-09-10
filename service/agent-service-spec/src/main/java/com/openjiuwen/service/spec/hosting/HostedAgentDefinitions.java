/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.hosting;

import com.openjiuwen.service.spec.spi.AgentHandler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, ordered startup declarations of hosted service targets.
 * Registration borrows the supplied handlers; it never creates or starts them.
 *
 * @since 0.1.2
 */
public final class HostedAgentDefinitions {
    private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private final List<Entry> entries;

    private final String defaultAgentId;

    private HostedAgentDefinitions(List<Entry> entries, String defaultAgentId) {
        this.entries = List.copyOf(entries);
        this.defaultAgentId = defaultAgentId;
    }

    /**
     * Creates a declaration builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates the syntax shared by registration and ingress selection.
     *
     * @param agentId the case-sensitive registration identifier
     * @return whether the identifier is valid
     */
    public static boolean isValidAgentId(String agentId) {
        return agentId != null && AGENT_ID.matcher(agentId).matches();
    }

    /**
     * Returns declarations in registration order.
     *
     * @return immutable declarations
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Returns the explicit default, or the first registered identifier.
     *
     * @return the registered default identifier
     */
    public String defaultAgentId() {
        return defaultAgentId;
    }

    /**
     * A stable routing identifier and its original execution object.
     *
     * @param agentId routing identifier, independent of any Core Agent ID
     * @param handler borrowed execution object
     */
    public record Entry(String agentId, AgentHandler handler) {
        public Entry {
            if (!isValidAgentId(agentId)) {
                throw new IllegalArgumentException("Invalid hosted agent ID");
            }
            Objects.requireNonNull(handler, "Hosted agent handler is required");
        }
    }

    /** Builds one aggregate declaration without changing handler lifecycle. */
    public static final class Builder {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        private final Map<AgentHandler, String> handlers = new IdentityHashMap<>();

        private String defaultAgentId;

        private Builder() {
        }

        /**
         * Registers an existing handler, retaining its identity and virtual methods.
         *
         * @param agentId unique routing identifier
         * @param handler existing handler, including subclasses
         * @return this builder
         */
        public Builder add(String agentId, AgentHandler handler) {
            Entry entry = new Entry(agentId, handler);
            if (entries.containsKey(agentId)) {
                throw new IllegalArgumentException("Duplicate hosted agent ID: " + agentId);
            }
            if (handlers.containsKey(handler)) {
                throw new IllegalArgumentException("Handler already registered as: " + handlers.get(handler));
            }
            entries.put(agentId, entry);
            handlers.put(handler, agentId);
            return this;
        }

        /**
         * Selects a default independently of declaration order.
         *
         * @param agentId identifier that must be registered when built
         * @return this builder
         */
        public Builder defaultAgent(String agentId) {
            if (!isValidAgentId(agentId)) {
                throw new IllegalArgumentException("Invalid default hosted agent ID");
            }
            defaultAgentId = agentId;
            return this;
        }

        /**
         * Freezes declarations; later builder changes cannot mutate the result.
         *
         * @return validated startup declarations
         */
        public HostedAgentDefinitions build() {
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("At least one hosted agent is required");
            }
            String selected = defaultAgentId;
            if (selected == null) {
                selected = entries.keySet().iterator().next();
            }
            if (!entries.containsKey(selected)) {
                throw new IllegalArgumentException("Default hosted agent is not registered: " + selected);
            }
            return new HostedAgentDefinitions(new ArrayList<>(entries.values()), selected);
        }
    }
}
