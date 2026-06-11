package com.openjiuwen.service.spec.lifecycle;

/**
 * Probe readiness state for Agent Service (consumed by {@code GET /health}, Issue #8).
 */
public interface AgentReadiness {

    /**
     * JVM / HTTP stack is up and accepting lifecycle-managed traffic.
     */
    boolean isProcessUp();

    /**
     * Agent / {@link com.openjiuwen.service.spec.spi.AgentHandler} finished init and can serve Query.
     */
    boolean isAgentLoaded();
}
