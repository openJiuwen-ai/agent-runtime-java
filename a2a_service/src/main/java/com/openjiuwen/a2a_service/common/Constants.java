package com.openjiuwen.a2a_service.common;

/**
 * Redis Key 模板函数。
 */
public final class Constants {

    private Constants() {}

    /**
     * 首轮请求的请求头和请求体缓存。
     */
    public static String sessionRequestKey(String convId) {
        return "session:" + convId + ":request";
    }
}
