/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.ExecuteCodeResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeStreamResult;
import com.openjiuwen.core.sysop.sandbox.SandboxEndpoint;

import java.util.Iterator;
import java.util.Map;

/**
 * HTTP-backed sandbox code execution provider.
 *
 * @since 2026-06-24
 */
public class HttpSandboxCodeProvider extends AbstractHttpSandboxProvider {
    private static final String OP_TYPE = "code";

    public HttpSandboxCodeProvider(SandboxEndpoint endpoint, SandboxGatewayConfig config) {
        super(endpoint, config);
    }

    /**
     * Executes code in the sandbox.
     *
     * @param code source code to execute
     * @param language source language identifier
     * @param timeout execution timeout in seconds
     * @param environment environment variables for execution
     * @param options additional sandbox-specific options
     * @return code execution result returned by the sandbox
     */
    public ExecuteCodeResult executeCode(
            String code,
            String language,
            int timeout,
            Map<String, String> environment,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "executeCode", params(
                "code", code,
                "language", language,
                "timeout", timeout,
                "environment", environment,
                "options", options), ExecuteCodeResult.class);
    }

    /**
     * Executes code in the sandbox and streams the output.
     *
     * @param code source code to execute
     * @param language source language identifier
     * @param timeout execution timeout in seconds
     * @param environment environment variables for execution
     * @param options additional sandbox-specific options
     * @return iterator of streaming code execution results
     */
    public Iterator<ExecuteCodeStreamResult> executeCodeStream(
            String code,
            String language,
            int timeout,
            Map<String, String> environment,
            Map<String, Object> options) {
        return invokeStream(OP_TYPE, "executeCodeStream", params(
                "code", code,
                "language", language,
                "timeout", timeout,
                "environment", environment,
                "options", options), ExecuteCodeStreamResult.class);
    }
}
