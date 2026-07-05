/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.DownloadFileResult;
import com.openjiuwen.core.sysop.result.DownloadFileStreamResult;
import com.openjiuwen.core.sysop.result.ExecuteCmdBackgroundResult;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.result.ExecuteCmdStreamResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeStreamResult;
import com.openjiuwen.core.sysop.result.ListDirsResult;
import com.openjiuwen.core.sysop.result.ListFilesResult;
import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.result.ReadFileStreamResult;
import com.openjiuwen.core.sysop.result.SearchFilesResult;
import com.openjiuwen.core.sysop.result.UploadFileResult;
import com.openjiuwen.core.sysop.result.UploadFileStreamResult;
import com.openjiuwen.core.sysop.result.WriteFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxCodeOperation;
import com.openjiuwen.core.sysop.sandbox.SandboxFsOperation;
import com.openjiuwen.core.sysop.sandbox.SandboxShellOperation;
import com.openjiuwen.service.adapters.common.external.ExternalCallExecutor;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Sandbox client decorator that applies Service external-call policies to
 * sandbox operations.
 *
 * @since 2026-06-24
 */
public class DecoratingSandboxClient extends SandboxClient {
    private final SandboxClient delegate;

    private final SandboxFsOperation fsOperation;

    private final SandboxShellOperation shellOperation;

    private final SandboxCodeOperation codeOperation;

    public DecoratingSandboxClient(String serverId, SandboxClient delegate,
        AgentCoreExternalProperties.SandboxPolicy policy) {
        super(delegate != null ? delegate.getConfig() : SandboxGatewayConfig.builder().build());
        this.delegate = delegate != null ? delegate : new SandboxClient(SandboxGatewayConfig.builder().build());
        ExternalCallExecutor executor = new ExternalCallExecutor("Sandbox", serverId, policy,
            ExternalSvcAdapterErrorCode.SANDBOX_OUTBOUND_CALL_FAILED, ExternalSvcAdapterErrorCode.SANDBOX_CIRCUIT_OPEN,
            ExternalSvcAdapterErrorCode.SANDBOX_RETRY_INTERRUPTED, ExternalSvcAdapterErrorCode.SANDBOX_TIMEOUT);
        this.fsOperation = new DecoratingSandboxFsOperation(getConfig(), this.delegate.fs(), executor);
        this.shellOperation = new DecoratingSandboxShellOperation(getConfig(), this.delegate.shell(), executor);
        this.codeOperation = new DecoratingSandboxCodeOperation(getConfig(), this.delegate.code(), executor);
    }

    @Override
    public SandboxFsOperation fs() {
        return fsOperation;
    }

    @Override
    public SandboxShellOperation shell() {
        return shellOperation;
    }

    @Override
    public SandboxCodeOperation code() {
        return codeOperation;
    }

    SandboxClient delegate() {
        return delegate;
    }

    private static final class DecoratingSandboxFsOperation extends SandboxFsOperation {
        private final SandboxFsOperation delegate;

        private final ExternalCallExecutor executor;

        private DecoratingSandboxFsOperation(SandboxGatewayConfig config, SandboxFsOperation delegate,
            ExternalCallExecutor executor) {
            super(config);
            this.delegate = delegate;
            this.executor = executor;
        }

        @Override
        public ReadFileResult readFile(String path, String mode, Integer head, Integer tail, int[] lineRange,
            String encoding, int chunkSize, Map<String, Object> options) {
            return executor.execute("fs", "readFile", true,
                () -> delegate.readFile(path, mode, head, tail, lineRange, encoding, chunkSize, options));
        }

        @Override
        public Iterator<ReadFileStreamResult> readFileStream(String path, String mode, Integer head, Integer tail,
            int[] lineRange, String encoding, int chunkSize, Map<String, Object> options) {
            return executor.execute("fs", "readFileStream", true,
                () -> delegate.readFileStream(path, mode, head, tail, lineRange, encoding, chunkSize, options));
        }

        @Override
        public WriteFileResult writeFile(String path, Object content, String mode, boolean shouldPrependNewline,
            boolean shouldAppendNewline, boolean shouldCreate, String permissions, String encoding,
            Map<String, Object> options) {
            return executor.execute("fs", "writeFile", false,
                () -> delegate.writeFile(path, content, mode, shouldPrependNewline, shouldAppendNewline,
                    shouldCreate, permissions, encoding, options));
        }

        @Override
        public UploadFileResult uploadFile(String localPath, String targetPath, boolean shouldOverwrite,
            boolean shouldCreateParentDirs, boolean shouldPreservePermissions, int chunkSize,
            Map<String, Object> options) {
            return executor.execute("fs", "uploadFile", false,
                () -> delegate.uploadFile(localPath, targetPath, shouldOverwrite, shouldCreateParentDirs,
                    shouldPreservePermissions, chunkSize, options));
        }

        @Override
        public Iterator<UploadFileStreamResult> uploadFileStream(String localPath, String targetPath,
            boolean shouldOverwrite, boolean shouldCreateParentDirs, boolean shouldPreservePermissions, int chunkSize,
            Map<String, Object> options) {
            return executor.execute("fs", "uploadFileStream", false,
                () -> delegate.uploadFileStream(localPath, targetPath, shouldOverwrite, shouldCreateParentDirs,
                    shouldPreservePermissions, chunkSize, options));
        }

        @Override
        public DownloadFileResult downloadFile(String sourcePath, String localPath, boolean shouldOverwrite,
            boolean shouldCreateParentDirs, boolean shouldPreservePermissions, int chunkSize,
            Map<String, Object> options) {
            return executor.execute("fs", "downloadFile", false,
                () -> delegate.downloadFile(sourcePath, localPath, shouldOverwrite, shouldCreateParentDirs,
                    shouldPreservePermissions, chunkSize, options));
        }

        @Override
        public Iterator<DownloadFileStreamResult> downloadFileStream(String sourcePath, String localPath,
            boolean shouldOverwrite, boolean shouldCreateParentDirs, boolean shouldPreservePermissions, int chunkSize,
            Map<String, Object> options) {
            return executor.execute("fs", "downloadFileStream", false,
                () -> delegate.downloadFileStream(sourcePath, localPath, shouldOverwrite, shouldCreateParentDirs,
                    shouldPreservePermissions, chunkSize, options));
        }

        @Override
        public ListFilesResult listFiles(String path, boolean shouldRecurse, Integer maxDepth, String sortBy,
            boolean shouldSortDescending, List<String> fileTypes, Map<String, Object> options) {
            return executor.execute("fs", "listFiles", true,
                () -> delegate.listFiles(path, shouldRecurse, maxDepth, sortBy, shouldSortDescending, fileTypes,
                    options));
        }

        @Override
        public ListDirsResult listDirectories(String path, boolean shouldRecurse, Integer maxDepth, String sortBy,
            boolean shouldSortDescending, Map<String, Object> options) {
            return executor.execute("fs", "listDirectories", true,
                () -> delegate.listDirectories(path, shouldRecurse, maxDepth, sortBy, shouldSortDescending, options));
        }

        @Override
        public SearchFilesResult searchFiles(String path, String pattern, List<String> excludePatterns) {
            return executor.execute("fs", "searchFiles", true,
                () -> delegate.searchFiles(path, pattern, excludePatterns));
        }
    }

    private static final class DecoratingSandboxShellOperation extends SandboxShellOperation {
        private final SandboxShellOperation delegate;

        private final ExternalCallExecutor executor;

        private DecoratingSandboxShellOperation(SandboxGatewayConfig config, SandboxShellOperation delegate,
            ExternalCallExecutor executor) {
            super(config);
            this.delegate = delegate;
            this.executor = executor;
        }

        @Override
        public ExecuteCmdResult executeCmd(String command, String cwd, int timeout, Map<String, String> environment,
            Map<String, Object> options) {
            int effectiveTimeout = timeout > 0 ? timeout : executor.timeoutSeconds();
            return executor.execute("shell", "executeCmd", false,
                () -> delegate.executeCmd(command, cwd, effectiveTimeout, environment, options));
        }

        @Override
        public Iterator<ExecuteCmdStreamResult> executeCmdStream(String command, String cwd, int timeout,
            Map<String, String> environment, Map<String, Object> options) {
            int effectiveTimeout = timeout > 0 ? timeout : executor.timeoutSeconds();
            return executor.execute("shell", "executeCmdStream", false,
                () -> delegate.executeCmdStream(command, cwd, effectiveTimeout, environment, options));
        }

        @Override
        public ExecuteCmdBackgroundResult executeCmdBackground(String command, String cwd,
            Map<String, String> environment, double grace, Map<String, Object> options) {
            return executor.execute("shell", "executeCmdBackground", false,
                () -> delegate.executeCmdBackground(command, cwd, environment, grace, options));
        }
    }

    private static final class DecoratingSandboxCodeOperation extends SandboxCodeOperation {
        private final SandboxCodeOperation delegate;

        private final ExternalCallExecutor executor;

        private DecoratingSandboxCodeOperation(SandboxGatewayConfig config, SandboxCodeOperation delegate,
            ExternalCallExecutor executor) {
            super(config);
            this.delegate = delegate;
            this.executor = executor;
        }

        @Override
        public ExecuteCodeResult executeCode(String code, String language, int timeout, Map<String, String> environment,
            Map<String, Object> options) {
            int effectiveTimeout = timeout > 0 ? timeout : executor.timeoutSeconds();
            return executor.execute("code", "executeCode", false,
                () -> delegate.executeCode(code, language, effectiveTimeout, environment, options));
        }

        @Override
        public Iterator<ExecuteCodeStreamResult> executeCodeStream(String code, String language, int timeout,
            Map<String, String> environment, Map<String, Object> options) {
            int effectiveTimeout = timeout > 0 ? timeout : executor.timeoutSeconds();
            return executor.execute("code", "executeCodeStream", false,
                () -> delegate.executeCodeStream(code, language, effectiveTimeout, environment, options));
        }
    }
}
