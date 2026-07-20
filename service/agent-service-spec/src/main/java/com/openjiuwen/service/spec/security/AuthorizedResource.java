/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an ingress REST handler method for fine-grained authorization via
 * {@link FineGrainedAuthorizer}.
 *
 * @since 0.1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthorizedResource {
    /**
     * Resource identifier (for example {@code query}, {@code a2a}, {@code agent-card}).
     *
     * @return resource name
     */
    String resource();

    /**
     * Action identifier (for example {@code execute}, {@code read}, {@code rpc}).
     *
     * @return action name
     */
    String action();
}
