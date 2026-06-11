package com.openjiuwen.service.app.controller.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebFlux stack Query controller ({@code POST /v1/query/reactive}).
 */
@RestController
@ConditionalOnClass(name = "reactor.core.publisher.Flux")
@ConditionalOnProperty(prefix = "openjiuwen.service.query.webflux", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class QueryWebFluxController {

    private final ObjectProvider<ServeOrchestrator> orchestratorProvider;
    private final ObjectProvider<AgentReadiness> readinessProvider;
    private final ObjectMapper objectMapper;

    public QueryWebFluxController(ObjectProvider<ServeOrchestrator> orchestratorProvider,
                                  ObjectProvider<AgentReadiness> readinessProvider,
                                  ObjectMapper objectMapper) {
        this.orchestratorProvider = orchestratorProvider;
        this.readinessProvider = readinessProvider;
        this.objectMapper = objectMapper;
    }

    @PostMapping(AgentServicePaths.QUERY_V1_REACTIVE)
    public Mono<ResponseEntity<?>> queryReactive(@RequestBody QueryRequest request,
                                                 @RequestHeader HttpHeaders headers) {
        return handleQuery(request, headers);
    }

    private Mono<ResponseEntity<?>> handleQuery(QueryRequest request, HttpHeaders headers) {
        QueryIngressSupport.ValidationResult validation = QueryIngressSupport.validateAndBuild(request, headers);
        if (!validation.valid()) {
            return Mono.just(ResponseEntity.status(validation.errorStatus()).body(validation.errorBody()));
        }
        if (!isAgentReady()) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(QueryIngressSupport.agentNotReady()));
        }
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(QueryIngressSupport.serviceUnavailable()));
        }
        if (request.isStream()) {
            Flux<ServerSentEvent<String>> flux = Flux.create(sink -> streamQuery(orchestrator, validation.serveRequest(), sink));
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                    .header(HttpHeaders.CONNECTION, "keep-alive")
                    .header("X-Accel-Buffering", "no")
                    .body(flux));
        }
        QueryResponse response = orchestrator.query(validation.serveRequest());
        return Mono.just(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response));
    }

    private boolean isAgentReady() {
        AgentReadiness readiness = readinessProvider.getIfAvailable();
        return readiness == null || readiness.isAgentLoaded();
    }

    private void streamQuery(ServeOrchestrator orchestrator,
                             com.openjiuwen.service.spec.dto.ServeRequest serveRequest,
                             reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        sink.onCancel(() -> cancelled.set(true));
        sink.onDispose(() -> cancelled.set(true));
        orchestrator.streamQuery(serveRequest, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                if (isCancelled()) {
                    return;
                }
                try {
                    sink.next(ServerSentEvent.builder(QuerySseSupport.toSseData(chunk, objectMapper)).build());
                } catch (Exception ex) {
                    cancelled.set(true);
                    throw new CancellationException(ex.getMessage());
                }
            }

            @Override
            public void onError(Throwable error) {
                if (!isCancelled()) {
                    sink.complete();
                }
            }

            @Override
            public void onComplete() {
                if (!isCancelled()) {
                    sink.complete();
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get() || sink.isCancelled();
            }
        });
    }
}
