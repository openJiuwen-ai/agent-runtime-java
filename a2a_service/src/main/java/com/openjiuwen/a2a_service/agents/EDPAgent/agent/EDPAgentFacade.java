package com.openjiuwen.a2a_service.agents.EDPAgent.agent;

import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.a2a_service.agents.EDPAgent.state.StateKeys;
import com.openjiuwen.a2a_service.agents.EDPAgent.stream.NorthboundStreamProcessor;
import com.openjiuwen.a2a_service.agents.EDPAgent.stream.SseEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EDPAgentFacade {

    private static volatile EDPAgentFacade DEFAULT_INSTANCE;

    private final Path agentRulePath;
    private final EDPAgentFactory factory;
    private ReActAgent agent;

    public EDPAgentFacade() {
        this(Paths.get("AgentRule.md"), new EDPAgentFactory());
    }

    public EDPAgentFacade(Path agentRulePath) {
        this(agentRulePath, new EDPAgentFactory());
    }

    public EDPAgentFacade(Path agentRulePath, EDPAgentFactory factory) {
        this.agentRulePath = agentRulePath;
        this.factory = factory;
    }

    public static synchronized EDPAgentFacade getDefault() {
        if (DEFAULT_INSTANCE == null) {
            DEFAULT_INSTANCE = new EDPAgentFacade();
        }
        return DEFAULT_INSTANCE;
    }

    public static synchronized void initializeDefault() throws IOException {
        getDefault().initialize();
    }

    public static synchronized Iterator<Map<String, Object>> agentStreamDefault(
            String query,
            String convId,
            Map<String, Object> cascadeResult,
            Map<String, Object> context
    ) throws IOException {
        return getDefault().agentStream(query, convId, cascadeResult, context);
    }

    public static EDPAgentFacade createEdpAgent(Path configPath) {
        return new EDPAgentFacade(configPath);
    }

    public synchronized void initialize() throws IOException {
        if (agent == null) {
            agent = factory.create(agentRulePath);
        }
    }

    public synchronized ReActAgent getAgent() {
        if (agent == null) {
            throw new IllegalStateException("EDPAgentFacade is not initialized");
        }
        return agent;
    }

    public Iterator<Map<String, Object>> agentStream(
            String query,
            String convId,
            Map<String, Object> cascadeResult,
            Map<String, Object> context
    ) throws IOException {
        initialize();

        String conversationId = convId != null ? convId : "";
        String effectiveQuery = cascadeResult == null ? query : "continue";
        boolean externalTurn = cascadeResult == null;
        AgentSessionApi session = AgentSessionApi.create(conversationId, null, getAgent().getCard());
        session.preRun(Map.of("query", effectiveQuery, "conversation_id", conversationId));

        Map<String, Object> stateUpdate = new LinkedHashMap<String, Object>();
        stateUpdate.put(
                StateKeys.ORIGINAL_BODY,
                context != null && context.get("body") instanceof Map<?, ?> map ? castMap(map) : Map.of()
        );
        if (cascadeResult != null) {
            stateUpdate.put(StateKeys.CASCADE_RESULT, cascadeResult);
            stateUpdate.put(StateKeys.PENDING_DELEGATE, null);
        } else {
            stateUpdate.put(StateKeys.MCP_PRODUCTS_DATA, null);
        }
        session.updateState(stateUpdate);

        Iterator<Object> iterator = getAgent().stream(
                Map.of("query", effectiveQuery, "conversation_id", conversationId),
                session,
                List.of(StreamMode.OUTPUT)
        );
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        NorthboundStreamProcessor processor = new NorthboundStreamProcessor();
        if (externalTurn) {
            events.add(eventMap("conversation_start", "", Map.of(), ""));
        }
        while (iterator.hasNext()) {
            for (SseEvent event : processor.process(iterator.next())) {
                events.add(event.toEventMap());
            }
        }
        for (SseEvent event : processor.finalizeEvents()) {
            events.add(event.toEventMap());
        }
        Map<String, Object> delegateRequest = buildDelegateRequest(session);
        if (delegateRequest != null) {
            events.add(delegateRequest);
            return events.iterator();
        }
        if (externalTurn) {
            events.add(eventMap("conversation_end", "", Map.of(), ""));
        }
        return events.iterator();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDelegateRequest(AgentSessionApi session) {
        Object pendingDelegate = session.getState(StateKeys.PENDING_DELEGATE);
        if (!(pendingDelegate instanceof Map<?, ?> map)) {
            return null;
        }
        return Map.of(
                "type", "delegate_request",
                "content", "",
                "plugin", "",
                "data", castMap(map)
        );
    }

    private Map<String, Object> eventMap(String type, String content, Map<String, Object> data, String plugin) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("content", content != null ? content : "");
        result.put("data", data != null ? data : Map.of());
        result.put("plugin", plugin != null ? plugin : "");
        return result;
    }

    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
