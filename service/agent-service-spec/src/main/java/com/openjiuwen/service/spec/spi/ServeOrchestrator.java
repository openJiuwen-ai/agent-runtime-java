package com.openjiuwen.service.spec.spi;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

/**
 * Ingress protocol-neutral orchestration kernel (AgentApp).
 */
public interface ServeOrchestrator {

    QueryResponse query(ServeRequest request);

    void streamQuery(ServeRequest request, QueryStreamObserver observer);
}
