/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.Member;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Owns protocol mapping at the remote-batch boundary.
 *
 * <p>It translates Core interrupts, persisted snapshots, and A2A task outcomes
 * to and from the coordinator's batch model. Runtime scheduling, persistence,
 * and remote I/O remain in {@link RemoteInvocationBatchCoordinator}.</p>
 *
 * @since 0.1.0
 */
final class RemoteInvocationBatchMapper {
    RemoteInvocationBatch parse(Map<String, Object> interrupt, ServeRequest request, String parentTaskId,
            SerialQueryStreamObserver observer) {
        List<Map<String, Object>> items = interruptItems(interrupt);
        List<Member> members = new ArrayList<>();
        Set<String> toolCallIds = new LinkedHashSet<>();
        Boolean isBatchResume = null;
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            String toolCallId = stringValue(item.get("toolCallId"));
            if (toolCallId.isBlank()) {
                throw new IllegalArgumentException("CORE_INTERRUPT_CORRELATION_MISSING");
            }
            if (!toolCallIds.add(toolCallId)) {
                throw new IllegalArgumentException("CORE_INTERRUPT_CORRELATION_CONFLICT: " + toolCallId);
            }
            Map<String, Object> context = item.get("context") instanceof Map<?, ?> rawContext
                    ? copyMap(rawContext)
                    : Map.of();
            if (!"a2a_delegate".equals(stringValue(context.get("_interrupt_kind")))) {
                throw new IllegalArgumentException("CORE_INTERRUPT_KIND_MIXED_UNSUPPORTED");
            }
            boolean isMemberResume = !(context.get("resume") instanceof Boolean isResumeFlag) || isResumeFlag;
            if (isBatchResume != null && isBatchResume != isMemberResume) {
                throw new IllegalArgumentException("CORE_INTERRUPT_RESUME_MIXED_UNSUPPORTED");
            }
            if (isBatchResume == null) {
                isBatchResume = isMemberResume;
            }
            int memberIndex = item.get("index") instanceof Number number ? number.intValue() : index;
            members.add(new Member(memberIndex, toolCallId, stringValue(item.get("toolName")),
                    stringValue(context.get("agentName")), stringValue(item.get("message"))));
        }
        members.sort(Comparator.comparingInt(member -> member.index));
        return new RemoteInvocationBatch(UUID.randomUUID().toString(), parentTaskId, request, observer, members,
                isBatchResume == null || isBatchResume);
    }

    RemoteInvocationBatch restore(Map<?, ?> rawBatch, ServeRequest request, String parentTaskId,
            SerialQueryStreamObserver observer) {
        Object rawMembers = rawBatch.get("members");
        if (!(rawMembers instanceof List<?> values)) {
            throw new IllegalStateException("REMOTE_BATCH_SNAPSHOT_INVALID");
        }
        List<Member> members = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> rawMember)) {
                throw new IllegalStateException("REMOTE_BATCH_MEMBER_INVALID");
            }
            int index = rawMember.get("index") instanceof Number number ? number.intValue() : members.size();
            Member member = new Member(index, stringValue(rawMember.get("toolCallId")),
                    stringValue(rawMember.get("toolName")), stringValue(rawMember.get("agentName")), "");
            member.state = MemberState.valueOf(stringValue(rawMember.get("state")));
            member.remoteTaskId = stringValue(rawMember.get("remoteTaskId"));
            member.resultCategory = optionalNonBlank(stringValue(rawMember.get("resultCategory"))).orElse(null);
            member.result = rawMember.get("result");
            restoreFailure(member);
            member.inputPrompt = optionalNonBlank(stringValue(rawMember.get("inputPrompt"))).orElse(null);
            members.add(member);
        }
        members.sort(Comparator.comparingInt(member -> member.index));
        String batchId = stringValue(rawBatch.get("batchId"));
        boolean shouldResume = !(rawBatch.get("resume") instanceof Boolean isResumeFlag) || isResumeFlag;
        return new RemoteInvocationBatch(batchId, parentTaskId, request, observer, members, shouldResume);
    }

    void applyOutcome(Member member, RemoteCallOutcome outcome, Throwable error) {
        member.completedAt = Instant.now();
        if (error != null) {
            Throwable cause = unwrap(error);
            if (cause instanceof TimeoutException) {
                member.fail(MemberState.TIMED_OUT, "REMOTE_TIMEOUT", "Remote invocation timed out");
            } else if (cause instanceof RejectedExecutionException) {
                member.fail(MemberState.FAILED, "REMOTE_OVERLOADED", safeMessage(cause));
            } else if (isRateLimited(cause)) {
                member.fail(MemberState.FAILED, "REMOTE_RATE_LIMITED", safeMessage(cause));
            } else if (isProtocolFailure(cause)) {
                member.fail(MemberState.FAILED, "REMOTE_PROTOCOL_ERROR", safeMessage(cause));
            } else {
                member.fail(MemberState.FAILED, "REMOTE_UNAVAILABLE", safeMessage(cause));
            }
            return;
        }
        if (outcome == null) {
            member.fail(MemberState.FAILED, "REMOTE_PROTOCOL_ERROR", "Remote call returned no outcome");
            return;
        }
        if (outcome.remoteTaskId() != null && !outcome.remoteTaskId().isBlank()) {
            member.remoteTaskId = outcome.remoteTaskId();
        }
        member.resultCategory = outcome.resultCategory();
        if (outcome.remoteState() == TaskState.TASK_STATE_INPUT_REQUIRED
                || outcome.remoteState() == TaskState.TASK_STATE_AUTH_REQUIRED) {
            member.state = MemberState.INPUT_REQUIRED;
            member.inputPrompt = outcome.inputPrompt() == null ? "Remote agent requires input" : outcome.inputPrompt();
        } else if (outcome.remoteState() == TaskState.TASK_STATE_COMPLETED) {
            member.state = MemberState.COMPLETED;
            member.result = outcome.result() == null ? "" : outcome.result();
        } else {
            String message = outcome.result() == null || outcome.result().isBlank()
                    ? "Remote task did not complete"
                    : outcome.result();
            member.fail(MemberState.FAILED, outcome.resultCategory(), message);
        }
    }

    RemoteCallOutcome callbackOutcome(Task task) {
        TaskStatus status = task.status();
        TaskState state = status == null ? null : status.state();
        String statusText = status == null || status.message() == null ? "" : extractText(status.message().parts());
        String taskText = A2aPartContent.extractTaskResult(task);
        if (isResultBearingNonTerminalState(state) && (!taskText.isBlank() || !statusText.isBlank())) {
            state = TaskState.TASK_STATE_COMPLETED;
        }
        if (state == TaskState.TASK_STATE_INPUT_REQUIRED || state == TaskState.TASK_STATE_AUTH_REQUIRED) {
            String inputPrompt = statusText.isBlank() ? "Remote agent requires input" : statusText;
            return new RemoteCallOutcome(task.id(), state, resultCategory(state), null, inputPrompt);
        }
        String resultText = state == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? statusText : taskText)
                : (statusText.isBlank() ? taskText : statusText);
        return new RemoteCallOutcome(task.id(), state, resultCategory(state), resultText, null);
    }

    Map<String, Object> snapshot(RemoteInvocationBatch batch, String state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("batchId", batch.batchId);
        snapshot.put("parentTaskId", batch.parentTaskId);
        snapshot.put("resume", batch.shouldResume);
        snapshot.put("state", state);
        List<Map<String, Object>> members = new ArrayList<>();
        for (Member member : batch.members) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("index", member.index);
            value.put("toolCallId", member.toolCallId);
            value.put("toolName", member.toolName);
            value.put("agentName", member.agentName);
            value.put("state", member.state.name());
            putIfNotBlank(value, "remoteTaskId", member.remoteTaskId);
            putIfNotBlank(value, "resultCategory", member.resultCategory);
            if (member.state != MemberState.INPUT_REQUIRED) {
                Object result = member.state == MemberState.COMPLETED && member.result != null
                        ? member.result
                        : toolResult(member);
                value.put("result", result);
            }
            putIfNotBlank(value, "inputPrompt", member.inputPrompt);
            members.add(value);
        }
        snapshot.put("members", members);
        return snapshot;
    }

    String shadowState(RemoteInvocationBatch batch) {
        boolean hasWaitingMember = batch.members.stream()
                .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);
        return hasWaitingMember ? "WAITING_INPUT" : "READY_TO_RESUME";
    }

    RemoteInvocationBatchCoordinator.BatchResolution resolution(RemoteInvocationBatch batch) {
        boolean hasWaitingMember = batch.members.stream()
                .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);
        if (hasWaitingMember) {
            return new RemoteInvocationBatchCoordinator.BatchResolution(batch.batchId, false, Map.of(),
                    publicInterrupt(batch), batch.shouldResume);
        }
        Map<String, Object> results = new LinkedHashMap<>();
        batch.members.forEach(member -> results.put(member.toolCallId, toolResult(member)));
        return new RemoteInvocationBatchCoordinator.BatchResolution(batch.batchId, true, results, Map.of(),
                batch.shouldResume);
    }

    private static void restoreFailure(Member member) {
        if (member.state == MemberState.COMPLETED || !(member.result instanceof Map<?, ?> error)) {
            return;
        }
        member.errorMessage = optionalNonBlank(stringValue(error.get("message"))).orElse(null);
        if (member.resultCategory == null) {
            member.resultCategory = optionalNonBlank(stringValue(error.get("code"))).orElse(null);
        }
    }

    private static List<Map<String, Object>> interruptItems(Map<String, Object> interrupt) {
        Object rawItems = interrupt.get("items");
        if (rawItems instanceof List<?> values) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("CORE_INTERRUPT_BATCH_INVALID");
                }
                items.add(copyMap(map));
            }
            if (items.isEmpty()) {
                throw new IllegalArgumentException("CORE_INTERRUPT_BATCH_EMPTY");
            }
            return items;
        }
        return List.of(new LinkedHashMap<>(interrupt));
    }

    private static Map<String, Object> publicInterrupt(RemoteInvocationBatch batch) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Member member : batch.members) {
            if (member.state != MemberState.INPUT_REQUIRED) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", member.toolCallId);
            putIfNotBlank(item, "toolName", member.toolName);
            item.put("message", member.inputPrompt == null ? "Remote agent requires input" : member.inputPrompt);
            items.add(item);
        }
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("message",
                items.size() == 1 ? items.get(0).get("message") : "Multiple remote agents require input");
        interrupt.put("items", items);
        return interrupt;
    }

    private static Object toolResult(Member member) {
        if (member.state == MemberState.COMPLETED) {
            return member.result == null ? "" : member.result;
        }
        if (member.result instanceof Map<?, ?>) {
            return member.result;
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("ok", false);
        error.put("code", member.resultCategory == null ? "REMOTE_FAILED" : member.resultCategory);
        error.put("message", member.errorMessage == null ? "Remote invocation failed" : member.errorMessage);
        error.put("remoteAgentId", member.agentName.isBlank() ? member.toolName : member.agentName);
        return error;
    }

    private static String resultCategory(TaskState state) {
        if (state == null) {
            return "REMOTE_PROTOCOL_ERROR";
        }
        return switch (state) {
            case TASK_STATE_COMPLETED -> "COMPLETED";
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED -> "INPUT_REQUIRED";
            case TASK_STATE_REJECTED -> "REMOTE_REJECTED";
            case TASK_STATE_FAILED -> "REMOTE_BUSINESS_FAILURE";
            default -> "REMOTE_PROTOCOL_ERROR";
        };
    }

    private static boolean isResultBearingNonTerminalState(TaskState state) {
        return state == null || state == TaskState.UNRECOGNIZED || state == TaskState.TASK_STATE_SUBMITTED
                || state == TaskState.TASK_STATE_WORKING;
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static boolean isRateLimited(Throwable error) {
        String message = safeMessage(error).toLowerCase(Locale.ROOT);
        return message.contains("429") || message.contains("rate limit") || message.contains("too many requests");
    }

    private static boolean isProtocolFailure(Throwable error) {
        String message = safeMessage(error).toLowerCase(Locale.ROOT);
        return message.contains("json-rpc") || message.contains("jsonrpc") || message.contains("protocol")
                || message.contains("malformed") || message.contains("parse error");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Map<String, Object> copyMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Optional<String> optionalNonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
