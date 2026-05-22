package com.openjiuwen.a2a_service.agents.EDPAgent.rail;

import com.openjiuwen.a2a_service.common.Constants;
import com.openjiuwen.a2a_service.common.RedisClient;
import com.openjiuwen.a2a_service.common.RedisHolder;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RedisInterruptRail extends BaseInterruptRail {

    private static final Logger logger = LoggerFactory.getLogger(RedisInterruptRail.class);

    public RedisInterruptRail() {
        super(List.of("query_redis_by_session_id"));
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
        String sessionId = ctx.getSession() != null ? ctx.getSession().getSessionId() : "";

        RedisClient redis = RedisHolder.get();
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        if (redis == null) {
            result.put("status", "error");
            result.put("message", "RedisClient 未初始化");
            return reject(result);
        }

        if (sessionId == null || sessionId.isBlank()) {
            result.put("status", "error");
            result.put("message", "当前会话无有效 session_id");
            return reject(result);
        }

        String redisKey = Constants.sessionRequestKey(sessionId);
        Optional<Map<String, Object>> jsonData = redis.getJsonAsMap(redisKey);

        if (jsonData.isPresent()) {
            result.put("status", "success");
            result.put("session_id", sessionId);
            result.put("data", jsonData.get());
        } else {
            result.put("status", "not_found");
            result.put("session_id", sessionId);
            result.put("message", "Redis 中未找到 key: " + redisKey);
        }

        logger.info("RedisInterruptRail: sessionId={}, redisKey={}, status={}", sessionId, redisKey, result.get("status"));
        return reject(result);
    }
}