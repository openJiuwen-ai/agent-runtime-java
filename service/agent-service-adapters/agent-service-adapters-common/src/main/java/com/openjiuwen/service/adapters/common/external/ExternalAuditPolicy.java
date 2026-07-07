/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

/**
 * Audit policy for external service calls.
 *
 * @since 2026-06-24
 */
public class ExternalAuditPolicy {
    private static final boolean DEFAULT_AUDIT_ENABLED = true;

    private boolean shouldAuditExternalCalls = DEFAULT_AUDIT_ENABLED;

    /**
     * Returns whether external call audit logging is enabled.
     *
     * @return true if external call audit logging is enabled
     */
    public boolean isEnabled() {
        return shouldAuditExternalCalls;
    }

    /**
     * Configures whether external call audit logging is enabled.
     *
     * @param shouldAuditExternalCalls true to record external call audit logs
     */
    public void setEnabled(boolean shouldAuditExternalCalls) {
        this.shouldAuditExternalCalls = shouldAuditExternalCalls;
    }

    /**
     * Creates an independent copy of this audit policy.
     *
     * @return independent audit policy copy
     */
    public ExternalAuditPolicy copy() {
        ExternalAuditPolicy copy = new ExternalAuditPolicy();
        copy.setEnabled(shouldAuditExternalCalls);
        return copy;
    }
}
