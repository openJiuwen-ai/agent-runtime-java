/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.Result;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeResult;
import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Registers sandbox client operations as local tools on a demo {@link ReActAgent}.
 *
 * @since 0.1.0
 */
public final class DecoratedSandboxToolRegistrar {
    private static final String DEFAULT_TOOL_ID_SERVER = "default";
    private static final String READ_FILE = "readFile";
    private static final String EXECUTE_CMD = "executeCmd";
    private static final String EXECUTE_CODE = "executeCode";

    private DecoratedSandboxToolRegistrar() {
    }

    /**
     * Registers readFile, executeCmd, and executeCode tools backed by a decorated sandbox client.
     *
     * @param agent sandbox demo agent
     * @param factory sandbox client factory
     * @return registered tool cards
     */
    public static List<ToolCard> register(ReActAgent agent, AgentCoreSandboxClientFactory factory) {
        return register(agent, factory, null);
    }

    /**
     * Registers readFile, executeCmd, and executeCode tools backed by a decorated sandbox client.
     *
     * @param agent sandbox demo agent
     * @param factory sandbox client factory
     * @param serverId sandbox server id, or {@code null} to use the first configured server
     * @return registered tool cards
     */
    public static List<ToolCard> register(ReActAgent agent, AgentCoreSandboxClientFactory factory, String serverId) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        if (factory == null) {
            return List.of();
        }

        SandboxClient client = factory.create(serverId);
        List<LocalFunction> tools = List.of(
                readFileTool(client, serverId),
                executeCmdTool(client, serverId),
                executeCodeTool(client, serverId));
        List<ToolCard> cards = new ArrayList<>(tools.size());
        for (LocalFunction tool : tools) {
            registerTool(agent, tool);
            cards.add(tool.getCard());
        }
        return cards;
    }

    private static void registerTool(ReActAgent agent, LocalFunction tool) {
        Result<ToolCard> result = Runner.resourceMgr().addTool(tool, agent.getCard().getId(), true);
        if (result.isError()) {
            throw new IllegalStateException(
                    "Failed to register sandbox tool " + tool.getCard().getName(),
                    result.getError());
        }
        agent.getAbilityManager().add(tool.getCard());
    }

    private static LocalFunction readFileTool(SandboxClient client, String serverId) {
        ToolCard card = toolCard(client.fs().listTools(), serverId, "fs", READ_FILE);
        return new LocalFunction(card, inputs -> {
            OptionalInt head = integerValue(inputs, "head");
            OptionalInt tail = integerValue(inputs, "tail");
            int[] lineRange = intArrayValue(inputs, "lineRange");
            ReadFileResult result = client.fs().readFile(
                    stringValue(inputs, "path", ""),
                    stringValue(inputs, "mode", "text"),
                    head.isPresent() ? Integer.valueOf(head.getAsInt()) : null,
                    tail.isPresent() ? Integer.valueOf(tail.getAsInt()) : null,
                    lineRange.length == 0 ? null : lineRange,
                    stringValue(inputs, "encoding", "UTF-8"),
                    intValue(inputs, "chunkSize", 0),
                    objectMapValue(inputs, "options"));
            return result;
        });
    }

    private static LocalFunction executeCmdTool(SandboxClient client, String serverId) {
        ToolCard card = toolCard(client.shell().listTools(), serverId, "shell", EXECUTE_CMD);
        return new LocalFunction(card, inputs -> {
            ExecuteCmdResult result = client.shell().executeCmd(
                    stringValue(inputs, "command", ""),
                    stringValue(inputs, "cwd", "."),
                    intValue(inputs, "timeout", 0),
                    stringMapValue(inputs, "environment"),
                    objectMapValue(inputs, "options"));
            return result;
        });
    }

    private static LocalFunction executeCodeTool(SandboxClient client, String serverId) {
        ToolCard card = toolCard(client.code().listTools(), serverId, "code", EXECUTE_CODE);
        return new LocalFunction(card, inputs -> {
            ExecuteCodeResult result = client.code().executeCode(
                    stringValue(inputs, "code", ""),
                    stringValue(inputs, "language", "python"),
                    intValue(inputs, "timeout", 0),
                    stringMapValue(inputs, "environment"),
                    objectMapValue(inputs, "options"));
            return result;
        });
    }

    private static ToolCard toolCard(List<ToolCard> cards, String serverId, String operation, String method) {
        ToolCard source = cards.stream()
                .filter(card -> method.equals(card.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Sandbox tool not found: " + method));
        return ToolCard.builder()
                .id(toolId(serverId, operation, method))
                .name(source.getName())
                .description(source.getDescription())
                .inputParams(source.getInputParams())
                .properties(source.getProperties())
                .build();
    }

    private static String toolId(String serverId, String operation, String method) {
        String resolvedServerId = serverId != null && !serverId.isBlank() ? serverId : DEFAULT_TOOL_ID_SERVER;
        return "sandbox." + resolvedServerId + "." + operation + "." + method;
    }

    private static String stringValue(Map<String, Object> inputs, String name, String defaultValue) {
        Object value = inputs != null ? inputs.get(name) : null;
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private static OptionalInt integerValue(Map<String, Object> inputs, String name) {
        Object value = inputs != null ? inputs.get(name) : null;
        if (value == null) {
            return OptionalInt.empty();
        }
        if (value instanceof Number number) {
            return OptionalInt.of(number.intValue());
        }
        return OptionalInt.of(Integer.parseInt(String.valueOf(value)));
    }

    private static int intValue(Map<String, Object> inputs, String name, int defaultValue) {
        OptionalInt value = integerValue(inputs, name);
        if (value.isPresent()) {
            return value.getAsInt();
        }
        return defaultValue;
    }

    private static int[] intArrayValue(Map<String, Object> inputs, String name) {
        Object value = inputs != null ? inputs.get(name) : null;
        if (value == null) {
            return new int[0];
        }
        if (value instanceof int[] arrayValue) {
            return arrayValue;
        }
        if (value instanceof List<?> listValue) {
            int[] array = new int[listValue.size()];
            for (int i = 0; i < listValue.size(); i++) {
                Object item = listValue.get(i);
                array[i] = item instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(item));
            }
            return array;
        }
        throw new IllegalArgumentException("Unsupported int array value: " + name);
    }

    private static Map<String, Object> objectMapValue(Map<String, Object> inputs, String name) {
        Object value = inputs != null ? inputs.get(name) : null;
        if (value instanceof Map<?, ?> mapValue) {
            return copyObjectMap(mapValue);
        }
        return Map.of();
    }

    private static Map<String, String> stringMapValue(Map<String, Object> inputs, String name) {
        Object value = inputs != null ? inputs.get(name) : null;
        if (!(value instanceof Map<?, ?> mapValue)) {
            return Map.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> mapValue) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
