/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * Integration journey for remote callback recovery through the HTTP receiver.
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.application.name=recovery-it")
@AutoConfigureTestRestTemplate
class CallbackRecoveryIntegrationTest {
    private static final String CALLBACK_PATH = "/a2a/push-notifications/callback";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TaskStore taskStore;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void acceptedCallbackRecoversMatchingRemoteBatchShadow() throws Exception {
        taskStore.save(shadowTask(), true);

        ResponseEntity<String> response = postCallback("notif-recovery-it", callbackBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(map(response.getBody()))
            .containsEntry("status", "accepted")
            .containsEntry("notificationId", "notif-recovery-it");
        Task shadow = taskStore.get("shadow:recovery-it:parent-recovery-it");
        Map<String, Object> batch = (Map<String, Object>) shadow.metadata().get("_remote_batch");
        List<Map<String, Object>> members = (List<Map<String, Object>>) batch.get("members");
        assertThat(batch).containsEntry("state", "READY_TO_RESUME");
        assertThat(members).singleElement().satisfies(member -> assertThat(member)
            .containsEntry("remoteTaskId", "remote-task-recovery-it")
            .containsEntry("state", "COMPLETED")
            .containsEntry("resultCategory", "COMPLETED")
            .containsEntry("result", "callback recovered"));
    }

    private ResponseEntity<String> postCallback(String notificationId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-A2A-Notification-Id", notificationId);
        return rest.postForEntity(CALLBACK_PATH, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String body) throws Exception {
        return mapper.readValue(body, Map.class);
    }

    private static Task shadowTask() {
        return Task.builder()
            .id("shadow:recovery-it:parent-recovery-it")
            .contextId("ctx-recovery-it")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
            .metadata(Map.of("_remote_batch", Map.of(
                "batchId", "batch-recovery-it",
                "parentTaskId", "parent-recovery-it",
                "state", "WAITING_INPUT",
                "members", List.of(Map.of(
                    "index", 0,
                    "toolCallId", "call-recovery-it",
                    "toolName", "remote-tool",
                    "agentName", "callee",
                    "state", "INPUT_REQUIRED",
                    "projectionSeq", 1,
                    "remoteTaskId", "remote-task-recovery-it",
                    "resultCategory", "INPUT_REQUIRED",
                    "inputPrompt", "waiting for remote callback")))))
            .build();
    }

    private static String callbackBody() {
        return """
            {
              "jsonrpc": "2.0",
              "result": {
                "task": {
                  "id": "remote-task-recovery-it",
                  "contextId": "remote-context-recovery-it",
                  "status": {"state": "TASK_STATE_COMPLETED"},
                  "artifacts": [{
                    "artifactId": "answer",
                    "parts": [{"kind": "text", "text": "callback recovered"}]
                  }]
                }
              },
              "notificationId": "notif-recovery-it"
            }
            """;
    }
}
