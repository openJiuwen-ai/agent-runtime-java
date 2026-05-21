package com.openjiuwen.a2a_service.agents.EDPAgent.tool;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EDPTools {

    private EDPTools() {
    }

    public static List<Tool> createAll() {
        List<Tool> tools = new ArrayList<Tool>();
        tools.add(createAskUserTool());
        tools.add(createCallMcpTool());
        tools.add(createCallVersatileTool());
        tools.add(createQueryRedisTool());
        return tools;
    }

    private static Tool createAskUserTool() {
        return new LocalFunction(ToolCard.builder()
                .id("ask_user")
                .name("ask_user")
                .description("追问用户信息。直接向用户提出问题，用户下一轮消息即为回答。")
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "question", Map.of(
                                        "type", "string",
                                        "description", "要问用户的问题"
                                )
                        ),
                        "required", List.of("question")
                ))
                .build(), EDPTools::handleAskUser);
    }

    private static Tool createCallMcpTool() {
        return new LocalFunction(ToolCard.builder()
                .id("call_mcp")
                .name("call_mcp")
                .description(
                        "MCP 产品推荐调用工具。"
                                + "通过 MCP SSE/HTTP 协议获取理财产品列表，系统自动完成灰度路由、去重和 sortType 轮换。"
                                + "首次推荐或多轮交互式推荐均通过此工具获取 MCP 产品数据。"
                )
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "mcp_params", Map.of(
                                        "type", "string",
                                        "description", "MCP 筛选参数 JSON 字符串。"
                                ),
                                "mcp_required_params", Map.of(
                                        "type", "string",
                                        "description", "MCP 必输参数 JSON 字符串，包含客户端环境信息。"
                                ),
                                "history_product_codes", Map.of(
                                        "type", "string",
                                        "description", "历史已推荐产品编码 JSON 数组字符串。首次推荐传空数组 []。"
                                ),
                                "current_sort_type", Map.of(
                                        "type", "integer",
                                        "description", "当前 sortType 整数值，首次推荐传 0。"
                                ),
                                "history_recommend_params", Map.of(
                                        "type", "string",
                                        "description", "历史推荐参数 JSON 字符串，首次推荐传空对象 {}。"
                                ),
                                "skill_context", Map.of(
                                        "type", "string",
                                        "description", "Skill 特有上下文参数 JSON 字符串，禁止传入 env_vars。"
                                )
                        ),
                        "required", List.of("mcp_params", "mcp_required_params")
                ))
                .build(), (inputs, kwargs) -> Map.of());
    }

    private static Tool createCallVersatileTool() {
        return new LocalFunction(ToolCard.builder()
                .id("call_versatile")
                .name("call_versatile")
                .description(
                        "通用业务工作流调用工具。"
                                + "将任务描述、上游工作流标识和归一化脚本路径传入，由系统自动调用 VersatileAdapter 完成工作流执行并返回结构化结果。"
                                + "理财场景可继续使用 query_intent；若外部工作流要求显式 agentName，可传 agent_name。"
                )
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query_description", Map.of(
                                        "type", "string",
                                        "description", "自然语言任务描述，传给工作流引擎。"
                                ),
                                "agent_name", Map.of(
                                        "type", "string",
                                        "description", "可选。上游工作流或智能体名称；当 Versatile 接口要求显式 agentName 时优先使用。"
                                ),
                                "query_intent", Map.of(
                                        "type", "string",
                                        "description", "可选。业务意图分类，用于本地规划和旧工作流路由。理财场景可选值：查询账户余额、快速转账、理财选品购买、理财推荐。"
                                ),
                                "query_response_analysis_scripts", Map.of(
                                        "type", "string",
                                        "description", "归一化脚本命令标识。Java 版会按命令标识做内置归一化。"
                                ),
                                "skill_context", Map.of(
                                        "type", "string",
                                        "description", "Skill 上下文 JSON 字符串，供归一化逻辑使用。"
                                )
                        ),
                        "required", List.of("query_description")
                ))
                .build(), (inputs, kwargs) -> Map.of());
    }

    private static Tool createQueryRedisTool() {
        return new LocalFunction(ToolCard.builder()
                .id("query_redis_by_session_id")
                .name("query_redis_by_session_id")
                .description(
                        "通过当前会话从 Redis 获取数据。"
                                + "系统自动使用当前会话的 session_id 作为 Redis key 查询对应 value 并返回。"
                                + "无需传入任何参数，session_id 由系统自动注入。"
                )
                .inputParams(Map.of(
                        "type", "object"
                ))
                .build(), (inputs, kwargs) -> Map.of());
    }

    private static Object handleAskUser(Map<String, Object> inputs, Map<String, Object> kwargs) {
        String question = stringValue(inputs, "question");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "success");
        result.put("question", question);
        return result;
    }

    private static String stringValue(Map<String, Object> values, String key) {
        Object value = values != null ? values.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }
}
