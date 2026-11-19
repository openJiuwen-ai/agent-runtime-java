/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Exercises JSON-RPC dispatch, SDK queues and actual task persistence together.
 *
 * @since 0.1.4
 */
@Timeout(20)
class A2ATaskLifecycleTest {
    private final ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);

    private final InMemoryTaskStore store = new InMemoryTaskStore();

    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32));

    private final ThreadPoolExecutor consumers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32));

    private final ThreadPoolExecutor processors = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1));

    private A2AAgentExecutor executor;

    private RequestHandler handler;

    private A2aJsonRpcController controller;

    private InMemoryQueueManager queues;

    @BeforeEach
    void setUp() {
        MainEventBus bus = new MainEventBus();
        queues = new InMemoryQueueManager(store, bus);
        executor = new A2AAgentExecutor(orchestrator, new A2AProtocolAdapter());
        executor.setDeferredTaskStore(store);
        MainEventBusProcessor processor = A2AAutoConfiguration.createEventProcessor(bus, store,
                mock(PushNotificationSender.class),
                queues, null);
        processors.submit(processor);
        handler = DefaultRequestHandler.create(executor, store, queues, mock(PushNotificationConfigStore.class),
                processor, workers, consumers);
        controller = new A2aJsonRpcController(handler);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.close();
        workers.shutdownNow();
        consumers.shutdownNow();
        processors.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(consumers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(processors.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cancelTaskRemainsUnsupportedWithoutChangingTask() {
        when(orchestrator.query(any())).thenReturn(interrupt());
        Task waiting = send(input("input-1", null));
        Task snapshot = get(waiting.id());
        JsonObject rejected = rpc("CancelTask", "{\"id\":\"" + waiting.id() + "\"}");
        assertThat(rejected.getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32601);
        assertThat(get(waiting.id())).isEqualTo(snapshot);
        assertThat(rpc("CancelTask", "{}").getAsJsonObject("error").get("code").getAsInt())
                .isEqualTo(-32601);
        verify(orchestrator, org.mockito.Mockito.never()).cancelActive(any());
    }

    @Test
    void completedTaskRetainsInputImageAndBusinessReply() {
        when(orchestrator.query(any())).thenReturn(reply("Use the transfer page"));
        Task done = send(input("input-1", null));

        assertThat(done.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertHistory(get(done.id()), "Locate the problem", "Use the transfer page");
        assertThat(get(done.id()).history().get(0).parts()).anyMatch(FilePart.class::isInstance);
        assertThat(get(done.id()).artifacts()).hasSize(1);
        assertThat(handler.onGetTask(new TaskQueryParams(done.id(), 1, null), context()).history()).hasSize(1);
        assertThat(get(done.id()).history()).hasSize(2);
        JsonObject wire = rpc("GetTask", "{\"id\":\"" + done.id() + "\"}");
        assertThat(wire.getAsJsonObject("result").getAsJsonArray("history")).hasSize(2);
    }

    @Test
    void resumeRetainsMessagesOnceAndInOrder() {
        when(orchestrator.query(any())).thenReturn(interrupt(), reply("Resolved"));
        Task waiting = send(input("input-1", null));
        assertThat(waiting.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        Task done = send(input("input-2", waiting.id()));

        assertHistory(get(done.id()), "Locate the problem", "Which page?", "Locate the problem", "Resolved");
        assertThat(get(done.id()).history()).extracting(Message::messageId).doesNotHaveDuplicates();
        verify(orchestrator, times(2)).query(any());
    }

    @Test
    void streamRetainsFinalReplyWithoutDeltasOrReasoningDuplicates()
            throws InterruptedException, ExecutionException, TimeoutException {
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "llm_reasoning", "index", 0, "payload", Map.of("content", "private reasoning"))));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "llm_output", "index", 0, "payload", Map.of("content", "Res"))));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "answer", "index", 0, "payload", Map.of("content", "Resolved"))));
            observer.onComplete(reply("Resolved"));
        })).when(orchestrator).streamQuery(any(), any());
        List<StreamingEventKind> events = collect(handler.onMessageSendStream(input("input-1", null), streamContext()));
        Task done = streamedTask(events);
        assertHistory(get(done.id()), "Locate the problem", "Resolved");
        assertThat(get(done.id()).status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(get(done.id()).artifacts()).isNotEmpty();
    }

    @Test
    void streamWithOnlyTextDeltasRetainsCombinedReply()
            throws InterruptedException, ExecutionException, TimeoutException {
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "Hello "));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "world"));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        List<StreamingEventKind> events = collect(handler.onMessageSendStream(input("input-1", null), streamContext()));
        Task done = streamedTask(events);
        assertHistory(get(done.id()), "Locate the problem", "Hello world");
    }

    @Test
    void streamingSuspensionRetainsReplyAndInputPrompt()
            throws InterruptedException, ExecutionException, TimeoutException {
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "Checking the page"));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "Which page?")));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        Task waiting = streamedTask(collect(handler.onMessageSendStream(input("input-1", null), streamContext())));
        assertThat(waiting.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        Task persisted = get(waiting.id());
        assertHistory(persisted, "Locate the problem", "Checking the page");
        assertThat(A2aPartContent.extract(persisted.status().message().parts())).isEqualTo("Which page?");
    }

    @Test
    void structuredStreamReplyKeepsDataPartsInHistory()
            throws InterruptedException, ExecutionException, TimeoutException {
        Map<String, Object> solution = Map.of("page", "transfer", "steps", List.of("Open", "Confirm"));
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "answer", "index", 0, "payload", Map.of("output", solution))));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        Task done = streamedTask(collect(handler.onMessageSendStream(input("input-1", null), streamContext())));
        assertThat(done.history()).hasSize(2);
        assertThat(done.history().get(1).parts()).singleElement().satisfies(part -> {
            assertThat(part).isInstanceOf(DataPart.class);
            if (part instanceof DataPart data) {
                assertThat(data.data()).isEqualTo(solution);
            }
        });
    }

    @Test
    void serializedStreamEnvelopesKeepOnlyBusinessText()
            throws InterruptedException, ExecutionException, TimeoutException {
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    "{\"type\":\"llm_reasoning\",\"index\":0,\"payload\":{\"content\":\"private\"}}"));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    "{\"type\":\"llm_output\",\"index\":0,\"payload\":{\"content\":\"Hello\"}}"));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        Task done = streamedTask(collect(handler.onMessageSendStream(input("input-1", null), streamContext())));
        assertHistory(done, "Locate the problem", "Hello");
    }

    @Test
    void deferredSyncInputRetainsQuestionAndOriginalIdentity() {
        when(orchestrator.query(any())).thenAnswer(invocation -> {
            ServeRequest request = invocation.getArgument(0);
            if (!"Deferred answer".equals(request.lastUserQuery())) {
                ServeRequest deferred = deferredInput();
                assertThat(executor.deferInput(deferred, "deferred-1")).isEqualTo("ACCEPTED");
                assertThat(executor.deferInput(deferred, "deferred-1")).isEqualTo("DUPLICATE");
                return interrupt();
            }
            return reply("Resolved");
        });
        Task done = send(input("input-1", null));
        assertHistory(get(done.id()), "Locate the problem", "Which page?", "Deferred answer", "Resolved");
        assertDeferredIdentity(get(done.id()));
        verify(orchestrator, times(2)).query(any());
    }

    @Test
    void deferredSyncRoundArchivesReplyBeforeQuestionAndNextInput() {
        when(orchestrator.query(any())).thenAnswer(invocation -> {
            ServeRequest request = invocation.getArgument(0);
            if (!"Deferred answer".equals(request.lastUserQuery())) {
                assertThat(executor.deferInput(deferredInput(), "deferred-1")).isEqualTo("ACCEPTED");
                return new QueryResponse(Map.of("content", "Checked the first page",
                        "_interrupt", Map.of("message", "Which page?")), "context-1");
            }
            return reply("Resolved");
        });
        Task done = send(input("input-1", null));
        assertHistory(get(done.id()), "Locate the problem", "Checked the first page", "Which page?",
                "Deferred answer", "Resolved");
        assertDeferredIdentity(get(done.id()));
        verify(orchestrator, times(2)).query(any());
    }

    @Test
    void syncSuspensionArchivesReplyWithoutDuplicatingQuestion() {
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "Checked the first page",
                "_interrupt", Map.of("message", "Which page?")), "context-1"));
        Task waiting = send(input("input-1", null));
        assertHistory(get(waiting.id()), "Locate the problem", "Checked the first page");
        assertThat(A2aPartContent.extract(get(waiting.id()).status().message().parts())).isEqualTo("Which page?");
    }

    @Test
    void syncSuspensionDoesNotArchivePromptAsSeparateBusinessReply() {
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "Which page?",
                "_interrupt", Map.of("message", "Which page?")), "context-1"), reply("Resolved"));
        Task waiting = send(input("input-1", null));
        Task done = send(input("input-2", waiting.id()));
        assertHistory(get(done.id()), "Locate the problem", "Which page?", "Locate the problem", "Resolved");
    }

    @Test
    void idleDeferredHistoryFailurePersistsFailedTask() {
        when(orchestrator.query(any())).thenReturn(interrupt());
        Task waiting = send(input("input-1", null));
        ServeRequest deferred = deferredInput();
        assertThat(executor.deferInput(deferred, "deferred-1")).isEqualTo("ACCEPTED");
        deferred.setMessages(List.of(Map.of("role", "user", "content", "Deferred answer",
                "parts", List.of(Map.of("kind", "invalid")))));
        var queue = queues.createOrTap(waiting.id());
        RequestContext context = new RequestContext.Builder().setTaskId(waiting.id())
                .setContextId(waiting.contextId()).setTask(waiting).build();
        executor.executeDeferred(context, deferred, new AgentEmitter(context, queue));
        assertThat(get(waiting.id()).status().state()).isEqualTo(TaskState.TASK_STATE_FAILED);
        assertThat(executor.hasDeferredInput(deferred, waiting.id())).isFalse();
        assertThat(executor.deferInput(deferredInput(), "another-input")).isEqualTo("NO_TARGET");
        verify(orchestrator, times(1)).query(any());
    }

    @Test
    void inlineDeferredHistoryFailurePersistsFailedTask() {
        when(orchestrator.query(any())).thenAnswer(invocation -> {
            ServeRequest deferred = deferredInput();
            assertThat(executor.deferInput(deferred, "deferred-1")).isEqualTo("ACCEPTED");
            deferred.setMessages(List.of(Map.of("role", "user", "content", "Deferred answer",
                    "parts", List.of(Map.of("kind", "invalid")))));
            return interrupt();
        });
        Task failed = send(input("input-1", null));
        assertThat(get(failed.id()).status().state()).isEqualTo(TaskState.TASK_STATE_FAILED);
        assertThat(executor.deferInput(deferredInput(), "another-input")).isEqualTo("NO_TARGET");
        verify(orchestrator, times(1)).query(any());
    }

    @Test
    void idleDeferredInputRetainsPartsAndDoesNotExecuteTwice() {
        when(orchestrator.query(any())).thenReturn(interrupt(), reply("Resolved"));
        Task waiting = send(input("input-1", null));
        ServeRequest deferred = deferredInput();
        assertThat(executor.deferInput(deferred, "deferred-1")).isEqualTo("ACCEPTED");
        assertThat(executor.deferInput(deferred, "deferred-1")).isEqualTo("DUPLICATE");
        var queue = queues.createOrTap(waiting.id());
        RequestContext context = new RequestContext.Builder().setTaskId(waiting.id())
                .setContextId(waiting.contextId()).setTask(waiting).build();
        executor.executeDeferred(context, deferred, new AgentEmitter(context, queue));
        executor.executeDeferred(context, deferred, new AgentEmitter(context, queue));
        Task done = get(waiting.id());
        assertHistory(done, "Locate the problem", "Which page?", "Deferred answer", "Resolved");
        assertDeferredIdentity(done);
        verify(orchestrator, times(2)).query(any());
    }

    @Test
    void deferredStreamArchivesEachRoundWithoutMixingReplies()
            throws InterruptedException, ExecutionException, TimeoutException {
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            if (!"Deferred answer".equals(request.lastUserQuery())) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "draft "));
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                        Map.of("type", "answer", "index", 0, "payload", Map.of("content", "draft reply"))));
                assertThat(executor.deferInput(deferredInput(), "deferred-1")).isEqualTo("ACCEPTED");
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "Which page?")));
            } else {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "final"));
            }
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        Task done = streamedTask(collect(handler.onMessageSendStream(input("input-1", null), streamContext())));
        assertHistory(done, "Locate the problem", "draft reply", "Which page?", "Deferred answer", "final");
        assertDeferredIdentity(done);
        verify(orchestrator, times(2)).streamQuery(any(), any());
    }

    @Test
    void multipleTerminalEventsRetainAllPartsInArrivalOrder()
            throws InterruptedException, ExecutionException, TimeoutException {
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "discarded delta"));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "answer", "index", 0, "payload", Map.of("content", "first"))));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "answer", "index", 1, "payload", Map.of("output", Map.of("page", "second")))));
            observer.onComplete(reply("fallback"));
        })).when(orchestrator).streamQuery(any(), any());
        Task done = streamedTask(collect(handler.onMessageSendStream(input("input-1", null), streamContext())));
        assertHistory(done, "Locate the problem", "first{\"page\":\"second\"}");
        assertThat(done.history().get(1).parts()).hasSize(2);
        assertThat(done.history().get(1).parts().get(1)).isInstanceOfSatisfying(DataPart.class,
                part -> assertThat(part.data()).isEqualTo(Map.of("page", "second")));
    }

    private static ServeRequest deferredInput() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("context-1");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "Deferred answer",
                "parts", List.of(Map.of("kind", "text", "text", "Deferred answer"),
                        Map.of("kind", "raw", "bytesBase64", "aW1hZ2U=", "mediaType", "image/png",
                                "filename", "deferred.png", "metadata", Map.of("source", "attachment"))),
                "metadata", Map.of("source", "deferred"))));
        return request;
    }

    private static void assertDeferredIdentity(Task task) {
        Message deferred = task.history().stream().filter(message -> "deferred-1".equals(message.messageId()))
                .findFirst().orElseThrow();
        assertThat(deferred.role()).isEqualTo(Message.Role.ROLE_USER);
        assertThat(deferred.taskId()).isEqualTo(task.id());
        assertThat(deferred.contextId()).isEqualTo(task.contextId());
        assertThat(deferred.metadata()).containsEntry("source", "deferred");
        assertThat(deferred.parts()).anySatisfy(part -> {
            assertThat(part).isInstanceOf(FilePart.class);
            if (part instanceof FilePart file) {
                assertThat(file.file()).isInstanceOfSatisfying(FileWithBytes.class,
                        bytes -> assertThat(bytes.bytes()).isEqualTo("aW1hZ2U="));
                assertThat(file.metadata()).containsEntry("source", "attachment");
            }
        });
        assertThat(task.history()).extracting(Message::messageId).doesNotHaveDuplicates();
    }

    private Task send(MessageSendParams params) {
        var result = handler.onMessageSend(params, context());
        assertThat(result).isInstanceOf(Task.class);
        if (result instanceof Task task) {
            return task;
        }
        throw new AssertionError("Expected task result");
    }

    private Task get(String taskId) {
        return handler.onGetTask(new TaskQueryParams(taskId, null, null), context());
    }

    private Task streamedTask(List<StreamingEventKind> events) {
        String taskId = events.stream().filter(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast).map(TaskStatusUpdateEvent::taskId).findFirst().orElseThrow();
        return get(taskId);
    }

    private JsonObject rpc(String method, String params) {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
        var request = new MockHttpServletRequest("POST", "/a2a");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        ResponseEntity<?> response = controller.handleJsonRpc(json, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(String.class);
        return JsonParser.parseString(response.getBody().toString()).getAsJsonObject();
    }

    private static MessageSendParams input(String messageId, String taskId) {
        Message message = Message.builder().messageId(messageId).taskId(taskId).contextId("context-1")
                .role(Message.Role.ROLE_USER).parts(List.of(new TextPart("Locate the problem"),
                        new FilePart(new FileWithBytes("image/png", "shot.png", "aW1hZ2U=")))).build();
        return MessageSendParams.builder().message(message).build();
    }

    private static QueryResponse reply(String text) {
        return new QueryResponse(Map.of("content", text), "context-1");
    }

    private static QueryResponse interrupt() {
        return new QueryResponse(Map.of("_interrupt", Map.of("message", "Which page?")), "context-1");
    }

    private static ServerCallContext context() {
        return new ServerCallContext(UnauthenticatedUser.INSTANCE, new ConcurrentHashMap<>(), Set.of());
    }

    private static ServerCallContext streamContext() {
        ServerCallContext context = context();
        context.getState().put("_a2a_stream", true);
        return context;
    }

    private static void assertHistory(Task task, String... texts) {
        assertThat(task.history()).extracting(message -> A2aPartContent.extract(message.parts()))
                .containsExactly(texts);
    }

    private static List<StreamingEventKind> collect(Flow.Publisher<StreamingEventKind> publisher)
            throws InterruptedException, ExecutionException, TimeoutException {
        StreamCollector collector = new StreamCollector();
        publisher.subscribe(collector);
        return collector.result.get(10, TimeUnit.SECONDS);
    }

    private static final class StreamCollector implements Flow.Subscriber<StreamingEventKind> {
        private final List<StreamingEventKind> events = new ArrayList<>();

        private final CompletableFuture<List<StreamingEventKind>> result = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamingEventKind event) {
            events.add(event);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(events);
        }
    }
}
