package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

/**
 * Agent framework adapter SPI (AgentFWAdapters).
 */
public interface AgentHandler {

    QueryResponse query(ServeRequest request);

    void streamQuery(ServeRequest request, QueryStreamObserver observer);

    /**
     * Start the handler before serving (e.g. AgentCore {@code Runner.start()}).
     * Invoked once during service init when {@code openjiuwen.service.auto-start-runner} is true.
     */
    default void start() {
    }

    /**
     * Stop the handler during service shutdown.
     */
    default void stop() {
    }
}
