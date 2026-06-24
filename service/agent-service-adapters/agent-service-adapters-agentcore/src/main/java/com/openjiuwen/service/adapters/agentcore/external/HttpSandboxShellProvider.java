/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.ExecuteCmdBackgroundResult;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.result.ExecuteCmdStreamResult;
import com.openjiuwen.core.sysop.sandbox.SandboxEndpoint;

import java.util.Iterator;
import java.util.Map;

/**
 * HTTP-backed sandbox shell command provider.
 *
 * @since 2026-06-24
 */
public class HttpSandboxShellProvider extends AbstractHttpSandboxProvider {
    private static final String OP_TYPE = "shell";

    public HttpSandboxShellProvider(SandboxEndpoint endpoint, SandboxGatewayConfig config) {
        super(endpoint, config);
    }

    /**
     * Executes a shell command in the sandbox.
     *
     * @param command shell command to execute
     * @param cwd working directory for the command
     * @param timeout command timeout in seconds
     * @param environment environment variables for the command
     * @param options additional sandbox-specific options
     * @return command execution result returned by the sandbox
     */
    public ExecuteCmdResult executeCmd(
            String command,
            String cwd,
            int timeout,
            Map<String, String> environment,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "executeCmd", params(
                "command", command,
                "cwd", cwd,
                "timeout", timeout,
                "environment", environment,
                "options", options), ExecuteCmdResult.class);
    }

    /**
     * Executes a shell command in the sandbox and streams the output.
     *
     * @param command shell command to execute
     * @param cwd working directory for the command
     * @param timeout command timeout in seconds
     * @param environment environment variables for the command
     * @param options additional sandbox-specific options
     * @return iterator of streaming command execution results
     */
    public Iterator<ExecuteCmdStreamResult> executeCmdStream(
            String command,
            String cwd,
            int timeout,
            Map<String, String> environment,
            Map<String, Object> options) {
        return invokeStream(OP_TYPE, "executeCmdStream", params(
                "command", command,
                "cwd", cwd,
                "timeout", timeout,
                "environment", environment,
                "options", options), ExecuteCmdStreamResult.class);
    }

    /**
     * Starts a background shell command in the sandbox.
     *
     * @param command shell command to execute
     * @param cwd working directory for the command
     * @param environment environment variables for the command
     * @param grace graceful shutdown period in seconds
     * @param options additional sandbox-specific options
     * @return background command result returned by the sandbox
     */
    public ExecuteCmdBackgroundResult executeCmdBackground(
            String command,
            String cwd,
            Map<String, String> environment,
            double grace,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "executeCmdBackground", params(
                "command", command,
                "cwd", cwd,
                "environment", environment,
                "grace", grace,
                "options", options), ExecuteCmdBackgroundResult.class);
    }
}
