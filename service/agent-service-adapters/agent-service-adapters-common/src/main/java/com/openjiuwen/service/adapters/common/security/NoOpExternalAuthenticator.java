/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import com.openjiuwen.service.spec.security.AuthMaterial;
import com.openjiuwen.service.spec.security.ExternalAuthConfig;
import com.openjiuwen.service.spec.security.ExternalAuthenticator;
import com.openjiuwen.service.spec.security.ExternalTargetRef;

/**
 * Default no-op outbound authenticator.
 *
 * @since 0.1.0
 */
public class NoOpExternalAuthenticator implements ExternalAuthenticator {
    @Override
    public AuthMaterial authenticate(ExternalTargetRef target, ExternalAuthConfig authConfig) {
        return AuthMaterial.none();
    }
}
