package com.openjiuwen.versatile_adapter.adapter;

import io.a2a.spec.*;

import java.util.List;

/**
 * VersatileAdapter A2A AgentCard 定义。
 *
 * 对应 Python: adapter/agent_card.py
 *
 * 创建 VERSATILE_ADAPTER_CARD 作为模块级常量 AgentCard。
 * 类名 AgentCardConfig 以避免与 SDK 的 io.a2a.spec.AgentCard 冲突。
 */
public final class AgentCardConfig {

    private AgentCardConfig() {}

    /**
     * VersatileAdapter 的 A2A AgentCard。
     *
     * - name: "VersatileAdapter"
     * - description: "Versatile 低代码平台工作流执行适配器"
     * - version: "1.0.0"
     * - streaming: true
     * - 一个 skill: "execute_workflow"
     * - input/output modes: "data"
     */
    public static final AgentCard VERSATILE_ADAPTER_CARD = AgentCard.builder()
            .name("VersatileAdapter")
            .description("Versatile 低代码平台工作流执行适配器")
            .version("1.0.0")
            .capabilities(AgentCapabilities.builder()
                    .streaming(true)
                    .pushNotifications(false)
                    .build())
            .defaultInputModes(List.of("data"))
            .defaultOutputModes(List.of("data"))
            .skills(List.of(
                    AgentSkill.builder()
                            .id("execute_workflow")
                            .name("执行工作流")
                            .description("调用 Versatile 低代码平台，执行指定工作流并返回结构化结果")
                            .tags(List.of("workflow", "versatile"))
                            .inputModes(List.of("data"))
                            .outputModes(List.of("data"))
                            .build()
            ))
            .supportedInterfaces(List.of(
                    new AgentInterface("JSONRPC", "")
            ))
            .build();
}
