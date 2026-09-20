/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.invocation;

import java.util.concurrent.Flow;

/** In-process access to the same A2A Task pipeline as Runtime HTTP ingress. */
public interface A2aRuntimeInvoker {
    /**
     * Dispatches once, immediately. SendMessage may block before returning.
     * Each onNext contains one complete JSON-RPC response without SSE framing.
     * Subscribe once, promptly, on a worker thread: SDK subscription may block.
     * Cancellation stops observation, not Agent execution or the Task.
     * The caller owns authentication and agent/task authorization.
     *
     * @param request request with target, JSON-RPC body and trusted headers
     * @return single-subscriber response publisher
     * @throws NullPointerException when request is null
     */
    Flow.Publisher<String> invoke(A2aInvocationRequest request);
}
