/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.concurrent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Provides reflective access to JDK 21 virtual-thread APIs while keeping the runtime compatible with JDK 17.
 *
 * @since 0.1.0
 */
public final class VirtualThreadSupport {
    private static final int MINIMUM_VIRTUAL_THREAD_VERSION = 21;

    private static final Optional<VirtualThreadMethods> VIRTUAL_THREAD_METHODS = resolveVirtualThreadMethods();

    private VirtualThreadSupport() {
    }

    /**
     * Checks whether the current Java runtime provides the stable virtual-thread APIs.
     *
     * @return {@code true} when virtual threads can be created
     */
    public static boolean isSupported() {
        return VIRTUAL_THREAD_METHODS.isPresent();
    }

    /**
     * Creates an executor that starts one named virtual thread for each submitted task.
     *
     * @param threadNamePrefix virtual-thread name prefix
     * @param exceptionHandler uncaught exception handler installed on each virtual thread
     * @return per-task virtual-thread executor
     * @throws IllegalStateException when the current Java runtime does not support virtual threads
     */
    public static ExecutorService newVirtualExecutor(String threadNamePrefix,
            Thread.UncaughtExceptionHandler exceptionHandler) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        Objects.requireNonNull(exceptionHandler, "exceptionHandler");
        VirtualThreadMethods methods = VIRTUAL_THREAD_METHODS.orElseThrow(() ->
                new IllegalStateException("Virtual threads are not available on this Java runtime"));
        try {
            Object builder = methods.ofVirtual().invoke(null);
            Object namedBuilder = methods.name().invoke(builder, threadNamePrefix + "-", 1L);
            Object configuredBuilder = methods.uncaughtExceptionHandler().invoke(namedBuilder, exceptionHandler);
            ThreadFactory threadFactory = ThreadFactory.class.cast(methods.factory().invoke(configuredBuilder));
            return ExecutorService.class.cast(methods.newThreadPerTaskExecutor().invoke(null, threadFactory));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Failed to access JDK virtual thread interfaces", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Failed to create virtual thread executor", exception.getTargetException());
        }
    }

    private static Optional<VirtualThreadMethods> resolveVirtualThreadMethods() {
        if (Runtime.version().feature() < MINIMUM_VIRTUAL_THREAD_VERSION) {
            return Optional.empty();
        }
        try {
            Method ofVirtual = Thread.class.getMethod("ofVirtual");
            Class<?> builderClass = ofVirtual.getReturnType();
            Method name = builderClass.getMethod("name", String.class, long.class);
            Method uncaughtExceptionHandler = builderClass.getMethod(
                    "uncaughtExceptionHandler", Thread.UncaughtExceptionHandler.class);
            Method factory = builderClass.getMethod("factory");
            Method newThreadPerTaskExecutor = Executors.class.getMethod(
                    "newThreadPerTaskExecutor", ThreadFactory.class);
            return Optional.of(new VirtualThreadMethods(ofVirtual, name, uncaughtExceptionHandler, factory,
                    newThreadPerTaskExecutor));
        } catch (NoSuchMethodException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private record VirtualThreadMethods(Method ofVirtual, Method name, Method uncaughtExceptionHandler,
            Method factory, Method newThreadPerTaskExecutor) {
    }
}
