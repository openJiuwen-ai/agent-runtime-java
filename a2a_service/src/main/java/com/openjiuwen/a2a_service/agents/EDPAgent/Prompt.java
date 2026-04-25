package com.openjiuwen.a2a_service.agents.EDPAgent;

/**
 * DPA Agent 系统提示词构建。
 */
public final class Prompt {

    private Prompt() {}

    public static String buildSystemPrompt() {
        return """
            你是动态规划智能体，使用思考-规划-执行-观察-反思的循环。
            可用工具：query_balance, transfer。
            每次只执行任务列表中的一个任务。执行一个任务后，思考是否需要更新规划。
            回复时要清晰说明当前执行结果和下一步。
            """;
    }
}
