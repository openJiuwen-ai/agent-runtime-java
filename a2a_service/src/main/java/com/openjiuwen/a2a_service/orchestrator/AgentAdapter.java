package com.openjiuwen.a2a_service.orchestrator;

import com.openjiuwen.a2a_service.common.Events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 事件 → A2A 事件转换。
 *
 * 转换规则：
 *   ThoughtEvent    → 中间输出（text part）
 *   AnswerEvent     → 最终回答（完成时附带 completed 状态）
 *   DelegateRequest → null（由 Executor 直接处理）
 */
public class AgentAdapter {

    /**
     * 将 DPA Agent 事件转换为 A2A 事件描述（用于 SSE 推送）。
     *
     * @return 事件描述 Map，或 null 如果不需要推送
     */
    public static Map<String, Object> agentEventToA2a(Object event, String taskId, String convId) {
        if (event instanceof Events.ThoughtEvent thought) {
            return Map.of(
                    "type", "artifact",
                    "taskId", taskId,
                    "contextId", convId,
                    "artifactId", UUID.randomUUID().toString(),
                    "content", thought.getContent(),
                    "lastChunk", false
            );
        }

        if (event instanceof Events.AnswerEvent answer) {
            if (answer.isFinal()) {
                return Map.of(
                        "type", "completed",
                        "taskId", taskId,
                        "contextId", convId,
                        "content", answer.getContent()
                );
            }
            return Map.of(
                    "type", "artifact",
                    "taskId", taskId,
                    "contextId", convId,
                    "artifactId", UUID.randomUUID().toString(),
                    "content", answer.getContent(),
                    "lastChunk", false
            );
        }

        return null;
    }
}
