/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

class A2AIngressHookTest {
    @Test
    void receiptCannotModifyOriginalTaskOrCrossConversation() {
        var params = MessageSendParams.builder().message(Message.builder().role(Message.Role.ROLE_USER).messageId("m")
                .contextId("c").taskId("business").parts(new TextPart("暂停")).build()).build();
        for (Task invalid : List.of(task("business", "c", TaskState.TASK_STATE_COMPLETED),
                task("receipt", "other", TaskState.TASK_STATE_COMPLETED),
                task("receipt", "c", TaskState.TASK_STATE_WORKING))) {
            assertThatThrownBy(() -> A2AIngressHook.validateReceipt(params, invalid))
                    .isInstanceOf(IllegalStateException.class);
        }
        A2AIngressHook.validateReceipt(params, task("receipt", "c", TaskState.TASK_STATE_COMPLETED));
    }

    @Test
    void streamEmitsOneReceiptDespiteReentrantDemand() {
        List<StreamingEventKind> events = new ArrayList<>();
        List<String> terminal = new ArrayList<>();
        Task receipt = task("receipt", "c", TaskState.TASK_STATE_COMPLETED);
        A2AIngressHook.receiptPublisher(receipt).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }
            @Override
            public void onNext(StreamingEventKind item) {
                events.add(item);
                subscription.request(1);
            }
            @Override
            public void onError(Throwable error) {
                terminal.add("error");
            }
            @Override
            public void onComplete() {
                terminal.add("complete");
            }
        });
        assertThat(events).containsExactly(receipt);
        assertThat(terminal).containsExactly("complete");
    }

    private static Task task(String id, String context, TaskState state) {
        return Task.builder().id(id).contextId(context).status(new TaskStatus(state)).build();
    }
}
