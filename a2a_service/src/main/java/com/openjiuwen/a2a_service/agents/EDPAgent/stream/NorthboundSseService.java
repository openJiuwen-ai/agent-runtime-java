package com.openjiuwen.a2a_service.agents.EDPAgent.stream;

import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.a2a_service.agents.EDPAgent.state.StateKeys;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NorthboundSseService {

    private final ReActAgent agent;
    private final NorthboundSseMapper mapper;

    public NorthboundSseService(ReActAgent agent) {
        this(agent, new NorthboundSseMapper());
    }

    public NorthboundSseService(ReActAgent agent, NorthboundSseMapper mapper) {
        this.agent = agent;
        this.mapper = mapper;
    }

    public List<String> stream(String query, String conversationId) {
        return streamInternal(query, conversationId, Map.of());
    }

    public List<String> stream(String query, String conversationId, Map<String, Object> originalBody) {
        return streamInternal(query, conversationId, originalBody);
    }

    public List<String> resume(InteractiveInput interactiveInput, String conversationId) {
        return streamInternal(interactiveInput, conversationId, Map.of());
    }

    private List<String> streamInternal(Object queryPayload, String conversationId, Map<String, Object> originalBody) {
        AgentSessionApi session = AgentSessionApi.create(conversationId, null, agent.getCard());
        if (originalBody != null && !originalBody.isEmpty()) {
            session.updateState(Map.of(StateKeys.ORIGINAL_BODY, originalBody));
        }
        Iterator<Object> iterator = agent.stream(
                Map.of("query", queryPayload, "conversation_id", conversationId),
                session,
                List.of(StreamMode.OUTPUT)
        );
        List<String> lines = new ArrayList<String>();
        long startedAt = System.nanoTime();
        NorthboundStreamProcessor processor = new NorthboundStreamProcessor();
        boolean interrupted = false;
        lines.add(mapper.buildNorthboundSseLine(
                new SseEvent("conversation_start", "本轮对话开启", Map.of(), ""),
                agent.getCard().getId(),
                conversationId,
                true,
                "",
                elapsedSeconds(startedAt)
        ));
        while (iterator.hasNext()) {
            Object item = iterator.next();
            for (SseEvent event : processor.process(item)) {
                if ("interrupt_start".equals(event.getEvent())) {
                    interrupted = true;
                }
                boolean success = !"error".equals(event.getEvent());
                String error = "error".equals(event.getEvent()) ? event.getContent() : "";
                lines.add(mapper.buildNorthboundSseLine(
                        event,
                        agent.getCard().getId(),
                        conversationId,
                        success,
                        error,
                        elapsedSeconds(startedAt)
                ));
            }
        }
        for (SseEvent event : processor.finalizeEvents()) {
            boolean success = !"error".equals(event.getEvent());
            String error = "error".equals(event.getEvent()) ? event.getContent() : "";
            lines.add(mapper.buildNorthboundSseLine(
                    event,
                    agent.getCard().getId(),
                    conversationId,
                    success,
                    error,
                    elapsedSeconds(startedAt)
            ));
        }
        Map<String, Object> pendingDelegate = getPendingDelegate(session);
        if (pendingDelegate != null) {
            lines.add(mapper.buildNorthboundSseLine(
                    new SseEvent("delegate_request", "", pendingDelegate, ""),
                    agent.getCard().getId(),
                    conversationId,
                    true,
                    "",
                    elapsedSeconds(startedAt)
            ));
            return lines;
        }
        if (!interrupted) {
            lines.add(mapper.buildNorthboundSseLine(
                    new SseEvent("conversation_end", "", Map.of(), ""),
                    agent.getCard().getId(),
                    conversationId,
                    true,
                    "",
                    elapsedSeconds(startedAt)
            ));
        }
        return lines;
    }

    private Map<String, Object> getPendingDelegate(AgentSessionApi session) {
        Object state = session.getState(StateKeys.PENDING_DELEGATE);
        if (!(state instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private double elapsedSeconds(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000_000.0;
    }
}
