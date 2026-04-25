package com.openjiuwen.versatile_adapter.adapter;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.tasks.AgentEmitter;
import io.a2a.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * VersatileAdapterExecutor — 无 LLM 的 A2A 执行器。
 *
 * 对应 Python: adapter/executor.py
 *
 * 实现 A2A SDK 的 AgentExecutor 接口：
 *   - execute(context, emitter): 接收任务 -> 调用 VersatileProxy -> 推送流式事件
 *   - cancel(context, emitter): 取消任务
 *
 * 纯透传层：不做 end 节点判断，不做状态管理，不存任何上下文。
 *
 * 使用 "前一个 chunk" 模式：延迟一次，确保最后一个 chunk 以 lastChunk=true 发送且不重复。
 */
public class Executor implements AgentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(Executor.class);

    private final VersatileProxy versatileProxy;

    public Executor(VersatileProxy versatileProxy) {
        this.versatileProxy = versatileProxy;
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        String convId = context.getContextId();

        logger.info("[VersatileAdapter] execute: convId={}, taskId={}", convId, taskId);

        // 1. 初始化 task（首轮 submit，续轮直接 startWork）
        if (context.getTask() == null) {
            emitter.submit();
        }
        emitter.startWork();

        // 2. 构建首次输入
        Map<String, Object> inputData = buildFirstInput(context.getMessage());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) inputData.getOrDefault("body", new HashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) inputData.getOrDefault("headers", new HashMap<>());

        logger.debug("[VersatileAdapter] 请求头: {}", headers);
        logger.debug("[VersatileAdapter] 请求体: {}", body);

        // 3. 用 "前一个 chunk" 模式流式调用 VersatileProxy
        //    延迟一次，确保最后一个 chunk 以 lastChunk=true 发送且不重复
        final Map<String, Object>[] prevChunk = new Map[]{null};
        final String finalConvId = convId != null ? convId : "";

        versatileProxy.dispatchStream(body, finalConvId, headers, chunk -> {
            // 如果有前一个 chunk，先推送它（lastChunk=false）
            if (prevChunk[0] != null) {
                emitter.addArtifact(List.of(new DataPart(prevChunk[0])));
            }
            prevChunk[0] = chunk;
        });

        // 4. 流结束：将最后一个 chunk 以 lastChunk=true 发出
        if (prevChunk[0] != null) {
            emitter.addArtifact(
                    List.of(new DataPart(prevChunk[0])),
                    null, null, null,
                    null, true    // lastChunk=true
            );
        } else {
            emitter.addArtifact(List.of(new TextPart("流结束")));
        }

        emitter.complete();
        logger.info("[VersatileAdapter] 流结束: convId={}, taskId={}", convId, taskId);
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        String convId = context.getContextId();

        logger.info("[VersatileAdapter] 任务已取消: convId={}, taskId={}", convId, taskId);
        emitter.cancel();
    }

    /**
     * 从 message parts 中构建首次输入。
     *
     * 优先使用 DataPart（结构化数据），回退到 TextPart（纯文本）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFirstInput(Message message) {
        if (message == null || message.parts() == null) {
            return Map.of("body", Map.of("input", Map.of("query", "")));
        }

        // 优先提取 DataPart
        for (Part<?> part : message.parts()) {
            if (part instanceof DataPart dataPart && dataPart.data() != null) {
                Object data = dataPart.data();
                if (data instanceof Map && !((Map<?, ?>) data).isEmpty()) {
                    return (Map<String, Object>) data;
                }
            }
        }

        // 回退：提取 TextPart
        String text = "";
        if (message.parts() != null) {
            for (Part<?> part : message.parts()) {
                if (part instanceof TextPart textPart && textPart.text() != null && !textPart.text().isEmpty()) {
                    text = textPart.text();
                    break;
                }
            }
        }
        return Map.of("body", Map.of("input", Map.of("query", text)));
    }
}
