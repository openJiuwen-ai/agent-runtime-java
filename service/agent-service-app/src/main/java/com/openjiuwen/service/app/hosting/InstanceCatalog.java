/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import java.util.List;

/**
 * Extension point enumerating the instances this process publishes for
 * unified-registry registration. Two shapes exist: hosted mode exposes every
 * published registration ({@link HostedInstanceCatalog}); standard
 * single-process mode exposes exactly one instance
 * ({@link StandardInstanceCatalog}). Implementations must be immutable views:
 * repeated calls return the same live set unless the underlying catalog
 * changed.
 *
 * @since 0.1.4
 */
public interface InstanceCatalog {

    /**
     * Returns the instances this process publishes.
     *
     * @return the instance descriptors, never null
     */
    List<InstanceDescriptor> instances();
}
