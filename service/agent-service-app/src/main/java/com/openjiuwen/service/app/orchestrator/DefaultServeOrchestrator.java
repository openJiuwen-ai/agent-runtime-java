package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Default orchestration: delegates to {@link AgentHandler} with unified error surfacing.
 */
public class DefaultServeOrchestrator implements ServeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultServeOrchestrator.class);

    private final AgentHandler agentHandler;
    private final ActiveStreamRegistry streamRegistry;

    public DefaultServeOrchestrator(AgentHandler agentHandler, ActiveStreamRegistry streamRegistry) {
        this.agentHandler = agentHandler;
        this.streamRegistry = streamRegistry;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        String conversationId = request.getConversationId();
        try {
            return agentHandler.query(request);
        } catch (Exception ex) {
            log.error("Query failed for conversation_id={}", conversationId, ex);
            throw ex;
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String conversationId = request.getConversationId() != null ? request.getConversationId() : "";
        StreamCancellationHandle handle = streamRegistry.register(conversationId);
        try {
            agentHandler.streamQuery(request, wrapObserver(observer, handle));
        } catch (CancellationException ex) {
            log.debug("Stream query cancelled for conversation_id={}", conversationId);
            observer.onComplete();
        } catch (Exception ex) {
            log.error("Stream query failed for conversation_id={}", conversationId, ex);
            Map<String, Object> errorEvent = new LinkedHashMap<>();
            errorEvent.put("type", "error");
            errorEvent.put("error", ex.getMessage() != null ? ex.getMessage() : ex.toString());
            observer.onNext(new QueryChunk("error", errorEvent));
            observer.onError(ex);
        } finally {
            streamRegistry.unregister(conversationId, handle);
        }
    }

    @Override
    public void cancelActive(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        log.debug("Cancelling active stream for conversation_id={}", conversationId);
        streamRegistry.cancel(conversationId);
    }

    private QueryStreamObserver wrapObserver(QueryStreamObserver observer, StreamCancellationHandle handle) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                if (isCancelled()) {
                    return;
                }
                observer.onNext(chunk);
            }

            @Override
            public void onError(Throwable error) {
                if (!isCancelled()) {
                    observer.onError(error);
                } else {
                    observer.onComplete();
                }
            }

            @Override
            public void onComplete() {
                observer.onComplete();
            }

            @Override
            public boolean isCancelled() {
                return handle.isCancelled() || observer.isCancelled();
            }
        };
    }
}
