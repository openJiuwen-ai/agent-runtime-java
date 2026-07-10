/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.external;

/**
 * Runtime exception raised by external service adapters.
 *
 * @since 2026-06-24
 */
public class ExternalSvcAdapterException extends RuntimeException {
    private final ExternalSvcAdapterErrorCode errorCode;

    public ExternalSvcAdapterException(ExternalSvcAdapterErrorCode errorCode, String message) {
        super(formatMessage(errorCode, message));
        this.errorCode = errorCode;
    }

    public ExternalSvcAdapterException(ExternalSvcAdapterErrorCode errorCode, String message, Throwable cause) {
        super(formatMessage(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    public ExternalSvcAdapterErrorCode getErrorCode() {
        return errorCode;
    }

    private static String formatMessage(ExternalSvcAdapterErrorCode errorCode, String message) {
        String code = errorCode != null ? errorCode.getCode() : "EXT_UNKNOWN";
        String defaultMessage = errorCode != null ? errorCode.getDefaultMessage() : "External service adapter error";
        String detail = message == null || message.isBlank() ? defaultMessage : message;
        return code + " " + detail;
    }
}
