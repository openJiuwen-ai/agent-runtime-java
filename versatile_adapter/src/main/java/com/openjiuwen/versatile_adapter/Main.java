package com.openjiuwen.versatile_adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * JDK11+ 原生 HTTP POST JSON 工具类【修复了请求体为空问题】
 */
public class Main {

    // 关键：强制使用 HTTP/1.1，禁止升级到 h2c → 解决 FastAPI 收不到请求体的问题！
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)  // 👈 这行是核心修复
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 发送POST JSON请求
     * @param url 请求接口地址
     * @param jsonBody 请求体json字符串
     * @param timeout 超时时间 秒
     * @return 接口响应字符串
     * @throws Exception 请求异常
     */
    public static String postJson(String url, String jsonBody, int timeout) throws Exception {
        // 构建请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // 发送请求
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * 重载方法：默认超时10秒
     */
    public static String postJson(String url, String jsonBody) throws Exception {
        return postJson(url, jsonBody, 10);
    }

    // 测试
    public static void main(String[] args) {
        try {
            String url = "http://127.0.0.1:10001/";
            String json = "{\"name\":\"测试\",\"msg\":\"你好FastAPI\"}";

            String result = postJson(url, json);
            System.out.println("返回结果：" + result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}