/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Mock LLM client that sleeps a fixed delay on each {@link #invoke} / {@link #stream} call only.
 * Agent invoke/stream, tools, rails and checkpoint paths remain real.
 *
 * @since 0.1.0
 */
public final class ConcurrencyMockModelClient extends BaseModelClient {
    private final long delayMs;

    /**
     * Creates a mock client with fixed invoke/stream latency.
     *
     * @param modelConfig model request configuration
     * @param modelClientConfig transport configuration
     * @param delayMs fixed delay applied on each LLM call
     */
    public ConcurrencyMockModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig,
        long delayMs) {
        super(modelConfig, modelClientConfig);
        this.delayMs = Math.max(0L, delayMs);
    }

    /**
     * Applies mock latency then returns a deterministic assistant message.
     *
     * @return planned assistant message
     * @throws Exception when mock latency sleep is interrupted
     */
    @Override
    public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
        Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout, Map<String, Object> kwargs)
        throws Exception {
        sleepForMockLatency();
        List<Map<String, Object>> convertedMessages = new ArrayList<>(convertMessagesToDict(messages));
        return ConcurrencyMockResponsePlanner.plan(convertedMessages);
    }

    /**
     * Applies mock latency then streams deterministic assistant chunks.
     *
     * @return iterator over planned assistant chunks
     * @throws Exception when mock latency sleep is interrupted
     */
    @Override
    public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
        String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
        Map<String, Object> kwargs) throws Exception {
        sleepForMockLatency();
        List<Map<String, Object>> convertedMessages = new ArrayList<>(convertMessagesToDict(messages));
        AssistantMessage message = ConcurrencyMockResponsePlanner.plan(convertedMessages);
        return toChunks(message).iterator();
    }

    /**
     * Image generation is not supported by the concurrency mock client.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public ImageGenerationResponse generateImage(List<UserMessage> messages, String model, String size,
        String negativePrompt, int n, boolean shouldPromptExtend, boolean shouldWatermark, int seed,
        Map<String, Object> kwargs) {
        throw new UnsupportedOperationException("concurrency mock LLM does not support image generation");
    }

    /**
     * Speech generation is not supported by the concurrency mock client.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public AudioGenerationResponse generateSpeech(List<UserMessage> messages, String model, String voice,
        String languageType, Map<String, Object> kwargs) {
        throw new UnsupportedOperationException("concurrency mock LLM does not support speech generation");
    }

    /**
     * Video generation is not supported by the concurrency mock client.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public VideoGenerationResponse generateVideo(List<UserMessage> messages, String imgUrl, String audioUrl, String model,
        String size, String resolution, int duration, boolean shouldPromptExtend, boolean shouldWatermark,
        String negativePrompt, Integer seed, Map<String, Object> kwargs) {
        throw new UnsupportedOperationException("concurrency mock LLM does not support video generation");
    }

    private void sleepForMockLatency() throws InterruptedException {
        if (delayMs > 0L) {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        }
    }

    private static List<AssistantMessageChunk> toChunks(AssistantMessage message) {
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            return List.of(AssistantMessageChunk.builder().content("").toolCalls(message.getToolCalls()).build());
        }
        String content = message.getContent() == null ? "" : String.valueOf(message.getContent());
        if (content.isEmpty()) {
            return List.of();
        }
        int split = Math.max(1, content.length() / 2);
        return List.of(
            AssistantMessageChunk.builder().content(content.substring(0, split)).build(),
            AssistantMessageChunk.builder().content(content.substring(split)).build());
    }
}
