/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.it.support;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal streaming agent for Core Runner integration tests (session echo
 * semantics).
 *
 * @since 0.1.0
 */
public class SessionEchoAgent {
    /**
     * Streams a reply while persisting conversation history in session state.
     *
     * @param inputs the runner inputs
     * @param session the agent session
     * @param streamModes the requested stream modes
     * @return the output iterator
     */
    @SuppressWarnings("unchecked")
    public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
        Map<String, Object> inputMap = (Map<String, Object>) inputs;
        String query = String.valueOf(inputMap.get("query"));
        Object priorState = session.getState("history");
        List<String> history = priorState instanceof List<?>
            ? new ArrayList<>((List<String>) priorState)
            : new ArrayList<>();
        String reply = "turn" + (history.size() + 1) + ":" + query;
        if (!history.isEmpty()) {
            reply += "|prev=" + String.join(",", history);
        }
        history.add(query);
        session.updateState(Map.of("history", history));
        return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", reply))).iterator();
    }
}
