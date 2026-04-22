package com.openjiuwen.a2a_service.common;

/**
 * DPA Agent 事件协议（与 A2A 完全解耦）。
 *
 * DPA 通过 agentStream() 生成器产生以下三种事件：
 *   - ThoughtEvent:      LLM 思考过程（流式中间输出）
 *   - AnswerEvent:       最终回答
 *   - DelegateRequest:   需要外部 Agent 处理子任务时的委托请求
 */
public final class Events {

    private Events() {}

    /**
     * LLM 思考过程（流式中间输出）。
     */
    public static class ThoughtEvent {
        public static final String TYPE = "thought";
        private final String content;

        public ThoughtEvent(String content) {
            this.content = content;
        }

        public String getType() { return TYPE; }
        public String getContent() { return content; }
    }

    /**
     * 最终回答。final=true 时 agentStream 即将结束。
     */
    public static class AnswerEvent {
        public static final String TYPE = "answer";
        private final String content;
        private final boolean isFinal;

        public AnswerEvent(String content, boolean isFinal) {
            this.content = content;
            this.isFinal = isFinal;
        }

        public String getType() { return TYPE; }
        public String getContent() { return content; }
        public boolean isFinal() { return isFinal; }
    }

    /**
     * DPA 需要外部 Agent 处理子任务时 yield 的委托对象。
     *
     * 协议约定：
     *   调用方（Orchestrator）收到此对象后，必须：
     *     1. 调用 targetAgent 完成子任务（如果有）
     *     2. 获得 workflowResult（dict）
     *     3. 以 cascadeResult=workflowResult 再次调用 agentStream()
     *   DPA 的 Runner Checkpoint 已保存，续轮时从中断点恢复。
     */
    public static class DelegateRequest {
        public static final String TYPE = "delegate";
        private final String intent;
        private final String targetAgent;
        private final String taskDescription;

        public DelegateRequest(String intent, String targetAgent, String taskDescription) {
            this.intent = intent;
            this.targetAgent = targetAgent;
            this.taskDescription = taskDescription;
        }

        public String getType() { return TYPE; }
        public String getIntent() { return intent; }
        public String getTargetAgent() { return targetAgent; }
        public String getTaskDescription() { return taskDescription; }
    }
}
