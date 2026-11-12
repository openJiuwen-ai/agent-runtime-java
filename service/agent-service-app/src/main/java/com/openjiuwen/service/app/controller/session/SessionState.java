/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

/**
 * Lifecycle state of a managed session as exposed by the session management
 * API (issue #117). A paused session is a user-requested suspension that can
 * continue via resume; a terminated session is final.
 *
 * @since 0.1.5
 */
public enum SessionState {
    /** The session has an in-flight or admitted execution. */
    RUNNING,
    /** The session exists without an in-flight execution. */
    IDLE,
    /** The session was suspended by an explicit user pause. */
    PAUSED,
    /** The session was terminated; the state is final. */
    TERMINATED
}
