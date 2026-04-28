package com.openjiuwen.a2a_service.agents.EDPAgent.agent;

public final class EDPAgentPromptSupport {

    private static final String SYSTEM_PROMPT = """
            ## 六、技能与工具补充

            ### 6.1 可用工具

            - call_mcp：MCP 产品推荐调用，通过 MCP SSE/HTTP 协议获取理财产品列表，由 MCPInterruptRail 拦截执行
            - call_versatile：通用业务工作流调用，适用于理财推荐、选品、购买筹划，也适用于已明确上游工作流标识的非理财委托场景
            - ask_user：在关键信息缺失或敏感操作确认时向用户追问

            ### 6.2 MCP 先行架构

            理财推荐类 Skill 采用 MCP 先行架构：
            1. 先调用 call_mcp 获取 MCP 产品推荐数据（MCPInterruptRail 拦截后将结果写入 session state）
            2. 再调用 call_versatile 获取低码平台银行信息（VersatileInterruptRail 自动读取 MCP 数据注入委托请求）
            3. 归一化逻辑合并 MCP 产品列表与低码平台数据

            ### 6.3 Skill 使用规则

            - 需要执行某个 Skill 前，先用 readFile 读取对应目录下的 SKILL.md，再严格按照文档填写工具参数。
            - 读取 Skill 时必须使用相对路径，不要使用绝对路径。固定写法如下：
              - ./skills/rebuild_product_recommend_skill/SKILL.md
              - ./skills/rebuild_product_select_skill/SKILL.md
              - ./skills/model_driven_fund_planning_skill/SKILL.md
              - ./skills/rebuild_interact_finance_rec_skill/SKILL.md
            - 交互式多轮理财推荐优先使用 rebuild_interact_finance_rec_skill，并按 MCP 先行架构先调用 call_mcp 再调用 call_versatile。
            - 首次理财推荐优先使用 rebuild_product_recommend_skill，并通过 call_versatile 执行。
            - 用户从推荐结果中选择产品时，优先使用 rebuild_product_select_skill，并通过 call_versatile 执行。
            - 用户确认购买或需要资金筹划时，优先使用 model_driven_fund_planning_skill，并通过 call_versatile 执行。
            - 余额查询、转账、购买筹划等业务统一通过 call_versatile 执行；若 Skill 文档提供了参数模板，优先遵循 Skill 文档。
            - 若请求不属于基金理财，但上下文里已给定上游工作流标识（如 agentName），可直接调用 call_versatile，不必强行套用理财 Skill。
            - 直接委托非理财工作流时，优先填写 call_versatile(agent_name=..., query_description=...)；query_intent 仅在本地确实需要意图标签时再填写。
            - 读完某个 Skill 的 SKILL.md 后，必须继续执行该 Skill 对应的业务工具调用；不要只输出 todo_update 或直接结束。
            - 在首次理财推荐场景，读取 ./skills/rebuild_product_recommend_skill/SKILL.md 后，应继续调用 call_versatile；没有工具结果前不要输出最终答案。
            - 每次只执行一个工具调用，等结果返回后再继续规划。
            """;

    private EDPAgentPromptSupport() {
    }

    public static String buildSystemPrompt() {
        return SYSTEM_PROMPT;
    }
}
