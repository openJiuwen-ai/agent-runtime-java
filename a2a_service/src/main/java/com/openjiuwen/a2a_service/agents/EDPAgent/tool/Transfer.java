package com.openjiuwen.a2a_service.agents.EDPAgent.tool;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 发起转账工具。
 *
 * 对应 Python: agents/EDPAgent/tool/transfer.py
 */
public class Transfer {

    private static final Logger logger = LoggerFactory.getLogger(Transfer.class);

    public static Map<String, Object> transfer(Map<String, Object> inputs) {
        String taskDescription = (String) inputs.getOrDefault("task_description", "");
        logger.info("====================");
        logger.info("进入工具: transfer, 任务描述: {}", taskDescription);
        logger.info("（外部接口调用和返回值判断已在 Rail 中处理）");
        logger.info("离开工具: transfer, 返回空字典占位符");
        logger.info("====================");
        return new HashMap<>();
    }

    public static final LocalFunction TRANSFER_TOOL = new LocalFunction(
            ToolCard.builder()
                    .id("transfer")
                    .name("transfer")
                    .description("发起转账。入参是自然语言描述的任务，只要意图匹配就直接调用，不需要追问用户的具体账户和金额信息。")
                    .inputParams(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "task_description", Map.of("type", "string", "description", "自然语言描述的任务，例如：'帮我转500块钱给张三'")
                            ),
                            "required", java.util.List.of("task_description")
                    ))
                    .build(),
            Transfer::transfer
    );
}
