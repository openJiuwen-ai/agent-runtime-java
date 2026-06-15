/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletResponse;

/**
 * MVC stack Query controller ({@code POST /v1/query} and legacy {@code POST /query}).
 */
@RestController
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QueryMvcController {

    private final ObjectProvider<ServeOrchestrator> orchestratorProvider;
    private final ObjectProvider<AgentReadiness> readinessProvider;
    private final ObjectMapper objectMapper;

    public QueryMvcController(ObjectProvider<ServeOrchestrator> orchestratorProvider,
                              ObjectProvider<AgentReadiness> readinessProvider,
                              ObjectMapper objectMapper) {
        this.orchestratorProvider = orchestratorProvider;
        this.readinessProvider = readinessProvider;
        this.objectMapper = objectMapper;
    }

    @PostMapping(AgentServicePaths.QUERY_V1)
    public SseEmitter queryV1(@RequestBody QueryRequest request,
                              @RequestHeader HttpHeaders headers,
                              HttpServletResponse response) throws IOException {
        return handleQuery(request, headers, response);
    }

    @PostMapping(AgentServicePaths.QUERY_LEGACY)
    @ConditionalOnProperty(prefix = "openjiuwen.service.query", name = "legacy-path-enabled",
            havingValue = "true", matchIfMissing = true)
    public SseEmitter queryLegacy(@RequestBody QueryRequest request,
                                  @RequestHeader HttpHeaders headers,
                                  HttpServletResponse response) throws IOException {
        return handleQuery(request, headers, response);
    }

    private SseEmitter handleQuery(QueryRequest request, HttpHeaders headers,
                                   HttpServletResponse response) throws IOException {
        QueryIngressSupport.ValidationResult validation = QueryIngressSupport.validateAndBuild(request, headers);
        if (!validation.valid()) {
            writeJson(response, validation.errorStatus(), validation.errorBody());
            return null;
        }
        if (!isAgentReady()) {
            writeJson(response, HttpStatus.SERVICE_UNAVAILABLE.value(), QueryIngressSupport.agentNotReady());
            return null;
        }
        ServeOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            writeJson(response, HttpStatus.SERVICE_UNAVAILABLE.value(), QueryIngressSupport.serviceUnavailable());
            return null;
        }
        if (request.isStream()) {
            return streamResponse(orchestrator, validation.serveRequest(), response);
        }
        QueryResponse queryResponse = orchestrator.query(validation.serveRequest());
        writeJson(response, HttpStatus.OK.value(), queryResponse);
        return null;
    }

    private SseEmitter streamResponse(ServeOrchestrator orchestrator,
                                      com.openjiuwen.service.spec.dto.ServeRequest serveRequest,
                                      HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader(HttpHeaders.CONNECTION, "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> {
            cancelled.set(true);
            emitter.complete();
        });
        emitter.onError(error -> cancelled.set(true));
        CompletableFuture.runAsync(() -> streamToEmitter(orchestrator, serveRequest, emitter, cancelled));
        return emitter;
    }

    private void streamToEmitter(ServeOrchestrator orchestrator,
                                 com.openjiuwen.service.spec.dto.ServeRequest serveRequest,
                                 SseEmitter emitter,
                                 AtomicBoolean cancelled) {
        orchestrator.streamQuery(serveRequest, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                if (isCancelled()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().data(QuerySseSupport.toSseData(chunk, objectMapper)));
                } catch (IOException | RuntimeException ex) {
                    cancelled.set(true);
                    throw new CancellationException(ex.getMessage());
                } catch (Exception ex) {
                    cancelled.set(true);
                    throw new RuntimeException(ex);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (!isCancelled()) {
                    emitter.complete();
                }
            }

            @Override
            public void onComplete() {
                if (!isCancelled()) {
                    emitter.complete();
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        });
    }

    private boolean isAgentReady() {
        AgentReadiness readiness = readinessProvider.getIfAvailable();
        return readiness == null || readiness.isAgentLoaded();
    }

    private void writeJson(HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), value);
    }
}
