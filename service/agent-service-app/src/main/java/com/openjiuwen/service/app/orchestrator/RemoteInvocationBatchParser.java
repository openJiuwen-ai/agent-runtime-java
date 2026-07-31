/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.Member;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Parses Core interrupts and persisted snapshots into remote invocation batches. */
final class RemoteInvocationBatchParser {
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
}
