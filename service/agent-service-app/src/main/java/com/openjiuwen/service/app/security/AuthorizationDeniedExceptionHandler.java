/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link AuthorizationDeniedException} to the ingress 403 JSON contract.
 *
 * @since 0.1.0
 */
@RestControllerAdvice
public class AuthorizationDeniedExceptionHandler {
    /**
     * Handles authorization denial.
     *
     * @param exception denial exception
     * @return error body
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> handleAuthorizationDenied(AuthorizationDeniedException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", "access denied");
        body.put("code", "ACCESS_DENIED");
        body.put("resource", exception.getResource());
        body.put("action", exception.getAction());
        if (exception.getReason() != null && !exception.getReason().isBlank()) {
            body.put("reason", exception.getReason());
        }
        return body;
    }
}
