/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;
import com.openjiuwen.service.spec.paths.AgentServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MVC stack Query controller ({@code POST /v1/query} and legacy
 * {@code POST /query}).
 *
 * @since 2026-07-03
 */
@RestController
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QueryMvcController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryMvcController.class);

    private final ObjectProvider<ServeOrchestrator> orchestratorProvider;

    private final ObjectProvider<AgentReadiness> readinessProvider;

    private final ObjectMapper objectMapper;

    public QueryMvcController(ObjectProvider<ServeOrchestrator> orchestratorProvider,
            ObjectProvider<AgentReadiness> readinessProvider, ObjectMapper objectMapper) {
        this.orchestratorProvider = orchestratorProvider;
        this.readinessProvider = readinessProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles {@code POST /v1/query} requests.
     *
     * @param rawBody the raw request body
     * @param headers the HTTP headers
     * @param servletRequest the servlet request
     * @param response the servlet response
     * @return an SSE emitter for streaming, or null for sync responses
     * @throws IOException on write errors
     */
    @PostMapping(AgentServicePaths.QUERY_V1)
    @AuthorizedResource(resource = "query", action = "execute")
    public SseEmitter queryV1(@RequestBody String rawBody, @RequestHeader HttpHeaders headers,
            jakarta.servlet.http.HttpServletRequest servletRequest, jakarta.servlet.http.HttpServletResponse response)
            throws IOException {
        return handleQuery(rawBody, headers, servletRequest, response);
    }

    SseEmitter handleQuery(String rawBody, HttpHeaders headers, jakarta.servlet.http.HttpServletRequest servletRequest,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        QueryRequest request = objectMapper.readValue(rawBody, QueryRequest.class);
        QueryIngressSupport.ValidationResult validation = QueryIngressSupport.validateAndBuild(request, headers);
        if (!validation.valid()) {
            writeJson(response, validation.errorStatus(), validation.errorBody());
            return null;
        }
        validateAndBuildMetadata(validation.serveRequest(), headers, servletRequest, rawBody);
        servletRequest.setAttribute(QueryIngressSupport.CONVERSATION_ID_ATTRIBUTE,
                validation.serveRequest().getConversationId());
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
            jakarta.servlet.http.HttpServletResponse response) {
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
            com.openjiuwen.service.spec.dto.ServeRequest serveRequest, SseEmitter emitter, AtomicBoolean cancelled) {
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
                }
            }

            @Override
            public void onError(Throwable error) {
                if (!isCancelled()) {
                    log.error("Stream query failed for conversation_id={}", serveRequest.getConversationId(), error);
                    emitter.completeWithError(error);
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

    private void writeJson(jakarta.servlet.http.HttpServletResponse response, int status, Object value)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), value);
    }

    void validateAndBuildMetadata(ServeRequest sr, HttpHeaders headers,
            jakarta.servlet.http.HttpServletRequest servletRequest, String rawBody) {
        Map<String, String> queryMap = new LinkedHashMap<>();
        servletRequest.getParameterMap().forEach((k, v) -> queryMap.put(k, v[0]));
        Map<String, Object> bodyMap;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(rawBody, Map.class);
            bodyMap = parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // fallback to raw body on parse error
            bodyMap = Map.of("_parse_error", "body_size=" + rawBody.length());
        }
        sr.setMetadata(QueryIngressSupport.buildMetadata(headers, queryMap, servletRequest.getRequestURI(), bodyMap));
    }
}

@RestController
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = QueryIngressSupport.LEGACY_PATH_PROPERTY, havingValue = "true", matchIfMissing = true)
class QueryLegacyMvcController {
    private final QueryMvcController delegate;

    QueryLegacyMvcController(QueryMvcController delegate) {
        this.delegate = delegate;
    }

    /**
     * Handles legacy {@code POST /query} requests.
     *
     * @param rawBody the raw request body
     * @param headers the HTTP headers
     * @param servletRequest the servlet request
     * @param response the servlet response
     * @return result from the delegate controller
     * @throws IOException on write errors
     */
    @PostMapping(AgentServicePaths.QUERY_LEGACY)
    @AuthorizedResource(resource = "query", action = "execute")
    public SseEmitter queryLegacy(@RequestBody String rawBody, @RequestHeader HttpHeaders headers,
            jakarta.servlet.http.HttpServletRequest servletRequest, jakarta.servlet.http.HttpServletResponse response)
            throws IOException {
        return delegate.handleQuery(rawBody, headers, servletRequest, response);
    }
}

@RestControllerAdvice(assignableTypes = {QueryMvcController.class, QueryLegacyMvcController.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.LOWEST_PRECEDENCE)
class QueryMvcExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryMvcExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> handleExecutionFailure(RuntimeException error,
            jakarta.servlet.http.HttpServletRequest request) {
        Object attribute = request.getAttribute(QueryIngressSupport.CONVERSATION_ID_ATTRIBUTE);
        if (!(attribute instanceof String conversationId)) {
            throw error;
        }
        log.error("Synchronous query failed for conversation_id={}", conversationId, error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(QueryIngressSupport.agentExecutionFailed(conversationId));
    }
}
