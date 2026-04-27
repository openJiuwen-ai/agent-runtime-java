package com.openjiuwen.a2a_service.agents.EDPAgent.rail;

import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;

final class SessionStreamSupport {

    private SessionStreamSupport() {
    }

    static void write(Session session, OutputSchema schema) {
        if (session instanceof AgentSessionApi agentSessionApi) {
            agentSessionApi.writeStream(schema);
        }
    }
}
