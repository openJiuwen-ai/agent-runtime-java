/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.service.adapters.common.external.ExternalCallExecutor;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;

/**
 * Remote client decorator that applies timeout, retry, circuit breaker, and
 * audit policies.
 *
 * @since 2026-06-24
 */
public class DecoratingRemoteClient implements RemoteClient {
    private final RemoteClientConfig config;

    private final RemoteClient delegate;

    private final AgentCoreExternalProperties.RemotePolicy policy;

    private final ExternalCallExecutor invokeExecutor;

    private final ExternalCallExecutor streamExecutor;

    public DecoratingRemoteClient(RemoteClientConfig config, RemoteClient delegate,
        AgentCoreExternalProperties.RemotePolicy policy) {
        this.config = config;
        this.delegate = delegate;
        this.policy = policy != null ? policy : new AgentCoreExternalProperties.RemotePolicy();
        this.invokeExecutor = new ExternalCallExecutor("Remote", remoteLabel(), this.policy,
            ExternalSvcAdapterErrorCode.REMOTE_OUTBOUND_CALL_FAILED, ExternalSvcAdapterErrorCode.REMOTE_CIRCUIT_OPEN,
            ExternalSvcAdapterErrorCode.REMOTE_RETRY_INTERRUPTED, ExternalSvcAdapterErrorCode.REMOTE_TIMEOUT);
        this.streamExecutor = new ExternalCallExecutor("Remote", remoteLabel(), this.policy,
            ExternalSvcAdapterErrorCode.REMOTE_STREAM_FAILED, ExternalSvcAdapterErrorCode.REMOTE_CIRCUIT_OPEN,
            ExternalSvcAdapterErrorCode.REMOTE_RETRY_INTERRUPTED, ExternalSvcAdapterErrorCode.REMOTE_TIMEOUT);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public boolean isStarted() {
        return delegate.isStarted();
    }

    @Override
    public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
        return executeInvoke(inputs, timeoutSeconds);
    }

    @Override
    public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
        Iterator<Object> iterator = streamExecutor.execute("remote", "stream", false, inputs,
            () -> streamDelegate(inputs, resolveTimeout(timeoutSeconds)));
        return new ExecutedRemoteIterator(iterator, inputs);
    }

    private Object executeInvoke(Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
        return invokeExecutor.execute("remote", "invoke", policy.isRetryInvoke(), inputs,
            () -> invokeDelegate(inputs, resolveTimeout(timeoutSeconds)));
    }

    private Object invokeDelegate(Map<String, Object> inputs, OptionalDouble timeoutSeconds) throws Exception {
        if (timeoutSeconds.isPresent()) {
            return delegate.invoke(inputs, timeoutSeconds.getAsDouble());
        }
        return delegate.invoke(inputs, null);
    }

    private Iterator<Object> streamDelegate(Map<String, Object> inputs, OptionalDouble timeoutSeconds)
        throws Exception {
        if (timeoutSeconds.isPresent()) {
            return delegate.stream(inputs, timeoutSeconds.getAsDouble());
        }
        return delegate.stream(inputs, null);
    }

    private OptionalDouble resolveTimeout(Double requestedTimeout) {
        if (requestedTimeout != null && requestedTimeout > 0) {
            return OptionalDouble.of(requestedTimeout);
        }
        if (policy.getTimeoutMs() <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(policy.getTimeoutMs() / 1000.0d);
    }

    private String remoteLabel() {
        if (config == null) {
            return "unknown";
        }
        return config.getId() + "/" + config.getName();
    }

    private final class ExecutedRemoteIterator implements Iterator<Object> {
        private final Iterator<Object> delegateIterator;

        private final Map<String, Object> inputs;

        private ExecutedRemoteIterator(Iterator<Object> delegateIterator, Map<String, Object> inputs) {
            this.delegateIterator = delegateIterator;
            this.inputs = inputs;
        }

        @Override
        public boolean hasNext() {
            return streamExecutor.execute("remote", "stream/hasNext", false, inputs, delegateIterator::hasNext);
        }

        @Override
        public Object next() {
            return streamExecutor.execute("remote", "stream/next", false, inputs, () -> {
                try {
                    return delegateIterator.next();
                } catch (NoSuchElementException ex) {
                    throw ex;
                }
            });
        }
    }
}
