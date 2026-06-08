package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

/**
 * Agent framework adapter SPI (AgentFWAdapters).
 */
public interface AgentHandler {

    QueryResponse query(ServeRequest request);

    void streamQuery(ServeRequest request, QueryStreamObserver observer);
}
