/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One native A2A endpoint for all five mock operations.
 *
 * @since 0.1.3
 */
@RestController
@Profile("mock")
@RequestMapping("/a2a/mock")
public class MockA2aController {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockBusinessService business;

    MockA2aController(MockBusinessService business) {
        this.business = business;
    }

    @GetMapping("/.well-known/agent-card.json")
    org.springframework.http.ResponseEntity<String> card() {
        String publicUrl = ServletUriComponentsBuilder.fromCurrentContextPath().path("/a2a/mock").toUriString();
        AgentCard card = AgentCard.builder().name("mock-tourism").description("虚构旅游业务 A2A 服务")
                .version("1.0").provider(new AgentProvider("moa-agent-demo", ""))
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder().id("tourism").name("演示旅游服务")
                        .description("虚构景点、行程和酒店").tags(List.of("demo")).build()))
                .securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", publicUrl, null, "1.0")))
                .url(publicUrl).preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
        return org.springframework.http.ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(json(card));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter rpc(@RequestBody String rawRequest) {
        SseEmitter emitter = new SseEmitter(30_000L);
        JsonNode rpcId = mapper.getNodeFactory().nullNode();
        try {
            JsonNode request = mapper.readTree(rawRequest);
            rpcId = request.path("id");
            if (!"2.0".equals(request.path("jsonrpc").asText())) {
                throw new IllegalArgumentException("Expected JSON-RPC 2.0");
            }
            if ("SendStreamingMessage".equals(request.path("method").asText())) {
                execute(emitter, rpcId, request.path("params").path("message"));
            } else {
                sendError(emitter, rpcId, -32601, "Method not supported");
            }
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            sendError(emitter, rpcId, -32602, exception.getMessage());
        } finally {
            emitter.complete();
        }
        return emitter;
    }

    private void execute(SseEmitter emitter, JsonNode rpcId, JsonNode message)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if (!message.path("taskId").asText().isBlank()) {
            throw new IllegalArgumentException("Mock operations are immediate; start a new task");
        }
        String context = message.path("contextId").asText(UUID.randomUUID().toString());
        String taskId = UUID.randomUUID().toString();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : message.path("parts")) {
            text.append(part.path("text").asText());
        }
        JsonNode envelope = mapper.readTree(text.toString());
        String intent = envelope.path("intent").asText();
        String input = envelope.path("query").asText();
        Map<String, Object> result = "attractions.search".equals(intent) && !input.startsWith("city=")
                ? business.attractionsQuery(input) : business.execute(intent, input);
        Artifact artifact = Artifact.builder().artifactId(UUID.randomUUID().toString())
                .parts(List.of(new TextPart(mapper.writeValueAsString(result)))).build();
        for (TaskState state : List.of(TaskState.TASK_STATE_SUBMITTED, TaskState.TASK_STATE_WORKING)) {
            stream(emitter, rpcId, new TaskStatusUpdateEvent(taskId, new TaskStatus(state), context, Map.of()));
        }
        stream(emitter, rpcId, new TaskArtifactUpdateEvent(taskId, artifact, context, false, true, Map.of()));
        stream(emitter, rpcId, new TaskStatusUpdateEvent(taskId,
                new TaskStatus(TaskState.TASK_STATE_COMPLETED), context, Map.of()));
    }

    private void stream(SseEmitter emitter, JsonNode rpcId, StreamingEventKind event) {
        Object id = rpcId.isNumber() ? rpcId.numberValue() : rpcId.asText("");
        send(emitter, json(new SendStreamingMessageResponse(id, event)));
    }

    private static String json(Object value) {
        try {
            return JsonUtil.toJson(value);
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize mock A2A response", exception);
        }
    }

    private void sendError(SseEmitter emitter, JsonNode rpcId, int code, String message) {
        var response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0").set("id", rpcId);
        response.putObject("error").put("code", code).put("message", message == null ? "Invalid request" : message);
        send(emitter, response.toString());
    }

    private static void send(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().name("jsonrpc").data(json));
        } catch (IOException exception) {
            throw new IllegalStateException("Mock SSE disconnected", exception);
        }
    }
}
