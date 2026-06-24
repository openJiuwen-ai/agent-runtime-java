/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.result.DownloadFileResult;
import com.openjiuwen.core.sysop.result.DownloadFileStreamResult;
import com.openjiuwen.core.sysop.result.ListDirsResult;
import com.openjiuwen.core.sysop.result.ListFilesResult;
import com.openjiuwen.core.sysop.result.ReadFileResult;
import com.openjiuwen.core.sysop.result.ReadFileStreamResult;
import com.openjiuwen.core.sysop.result.SearchFilesResult;
import com.openjiuwen.core.sysop.result.UploadFileResult;
import com.openjiuwen.core.sysop.result.UploadFileStreamResult;
import com.openjiuwen.core.sysop.result.WriteFileResult;
import com.openjiuwen.core.sysop.sandbox.SandboxEndpoint;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HTTP-backed sandbox file-system provider.
 *
 * @since 2026-06-24
 */
public class HttpSandboxFsProvider extends AbstractHttpSandboxProvider {
    private static final String OP_TYPE = "fs";

    public HttpSandboxFsProvider(SandboxEndpoint endpoint, SandboxGatewayConfig config) {
        super(endpoint, config);
    }

    /**
     * Reads a file from the sandbox file system.
     *
     * @param path sandbox file path
     * @param mode read mode requested by the caller
     * @param head maximum number of leading lines to read
     * @param tail maximum number of trailing lines to read
     * @param lineRange inclusive line range to read
     * @param encoding file encoding name
     * @param chunkSize preferred read chunk size
     * @param options additional sandbox-specific options
     * @return read-file result returned by the sandbox
     */
    public ReadFileResult readFile(
            String path,
            String mode,
            Integer head,
            Integer tail,
            int[] lineRange,
            String encoding,
            int chunkSize,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "readFile", params(
                "path", path,
                "mode", mode,
                "head", head,
                "tail", tail,
                "lineRange", lineRange,
                "encoding", encoding,
                "chunkSize", chunkSize,
                "options", options), ReadFileResult.class);
    }

    /**
     * Opens a streaming read for a file in the sandbox file system.
     *
     * @param path sandbox file path
     * @param mode read mode requested by the caller
     * @param head maximum number of leading lines to read
     * @param tail maximum number of trailing lines to read
     * @param lineRange inclusive line range to read
     * @param encoding file encoding name
     * @param chunkSize preferred stream chunk size
     * @param options additional sandbox-specific options
     * @return iterator of streaming read-file results
     */
    public Iterator<ReadFileStreamResult> readFileStream(
            String path,
            String mode,
            Integer head,
            Integer tail,
            int[] lineRange,
            String encoding,
            int chunkSize,
            Map<String, Object> options) {
        return invokeStream(OP_TYPE, "readFileStream", params(
                "path", path,
                "mode", mode,
                "head", head,
                "tail", tail,
                "lineRange", lineRange,
                "encoding", encoding,
                "chunkSize", chunkSize,
                "options", options), ReadFileStreamResult.class);
    }

    /**
     * Writes content to a file in the sandbox file system.
     *
     * @param path sandbox file path
     * @param content content to write
     * @param mode write mode requested by the caller
     * @param shouldPrependNewline whether to prepend a newline before content
     * @param shouldAppendNewline whether to append a newline after content
     * @param shouldCreateIfNotExist whether to create the file when absent
     * @param permissions file permissions to apply
     * @param encoding file encoding name
     * @param options additional sandbox-specific options
     * @return write-file result returned by the sandbox
     */
    public WriteFileResult writeFile(
            String path,
            Object content,
            String mode,
            boolean shouldPrependNewline,
            boolean shouldAppendNewline,
            boolean shouldCreateIfNotExist,
            String permissions,
            String encoding,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "writeFile", params(
                "path", path,
                "content", content,
                "mode", mode,
                "prependNewline", shouldPrependNewline,
                "appendNewline", shouldAppendNewline,
                "createIfNotExist", shouldCreateIfNotExist,
                "permissions", permissions,
                "encoding", encoding,
                "options", options), WriteFileResult.class);
    }

    /**
     * Uploads a local file to the sandbox file system.
     *
     * @param localPath local source file path
     * @param targetPath sandbox target file path
     * @param shouldOverwrite whether to overwrite an existing target file
     * @param shouldCreateParentDirs whether to create missing target parent directories
     * @param shouldPreservePermissions whether to preserve source file permissions
     * @param chunkSize preferred upload chunk size
     * @param options additional sandbox-specific options
     * @return upload result returned by the sandbox
     */
    public UploadFileResult uploadFile(
            String localPath,
            String targetPath,
            boolean shouldOverwrite,
            boolean shouldCreateParentDirs,
            boolean shouldPreservePermissions,
            int chunkSize,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "uploadFile", params(
                "localPath", localPath,
                "targetPath", targetPath,
                "overwrite", shouldOverwrite,
                "createParentDirs", shouldCreateParentDirs,
                "preservePermissions", shouldPreservePermissions,
                "chunkSize", chunkSize,
                "options", options), UploadFileResult.class);
    }

    /**
     * Opens a streaming upload to the sandbox file system.
     *
     * @param localPath local source file path
     * @param targetPath sandbox target file path
     * @param shouldOverwrite whether to overwrite an existing target file
     * @param shouldCreateParentDirs whether to create missing target parent directories
     * @param shouldPreservePermissions whether to preserve source file permissions
     * @param chunkSize preferred upload chunk size
     * @param options additional sandbox-specific options
     * @return iterator of streaming upload results
     */
    public Iterator<UploadFileStreamResult> uploadFileStream(
            String localPath,
            String targetPath,
            boolean shouldOverwrite,
            boolean shouldCreateParentDirs,
            boolean shouldPreservePermissions,
            int chunkSize,
            Map<String, Object> options) {
        return invokeStream(OP_TYPE, "uploadFileStream", params(
                "localPath", localPath,
                "targetPath", targetPath,
                "overwrite", shouldOverwrite,
                "createParentDirs", shouldCreateParentDirs,
                "preservePermissions", shouldPreservePermissions,
                "chunkSize", chunkSize,
                "options", options), UploadFileStreamResult.class);
    }

    /**
     * Downloads a sandbox file to a local path.
     *
     * @param sourcePath sandbox source file path
     * @param localPath local target file path
     * @param shouldOverwrite whether to overwrite an existing local file
     * @param shouldCreateParentDirs whether to create missing local parent directories
     * @param shouldPreservePermissions whether to preserve sandbox file permissions
     * @param chunkSize preferred download chunk size
     * @param options additional sandbox-specific options
     * @return download result returned by the sandbox
     */
    public DownloadFileResult downloadFile(
            String sourcePath,
            String localPath,
            boolean shouldOverwrite,
            boolean shouldCreateParentDirs,
            boolean shouldPreservePermissions,
            int chunkSize,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "downloadFile", params(
                "sourcePath", sourcePath,
                "localPath", localPath,
                "overwrite", shouldOverwrite,
                "createParentDirs", shouldCreateParentDirs,
                "preservePermissions", shouldPreservePermissions,
                "chunkSize", chunkSize,
                "options", options), DownloadFileResult.class);
    }

    /**
     * Opens a streaming download for a sandbox file.
     *
     * @param sourcePath sandbox source file path
     * @param localPath local target file path
     * @param shouldOverwrite whether to overwrite an existing local file
     * @param shouldCreateParentDirs whether to create missing local parent directories
     * @param shouldPreservePermissions whether to preserve sandbox file permissions
     * @param chunkSize preferred download chunk size
     * @param options additional sandbox-specific options
     * @return iterator of streaming download results
     */
    public Iterator<DownloadFileStreamResult> downloadFileStream(
            String sourcePath,
            String localPath,
            boolean shouldOverwrite,
            boolean shouldCreateParentDirs,
            boolean shouldPreservePermissions,
            int chunkSize,
            Map<String, Object> options) {
        return invokeStream(OP_TYPE, "downloadFileStream", params(
                "sourcePath", sourcePath,
                "localPath", localPath,
                "overwrite", shouldOverwrite,
                "createParentDirs", shouldCreateParentDirs,
                "preservePermissions", shouldPreservePermissions,
                "chunkSize", chunkSize,
                "options", options), DownloadFileStreamResult.class);
    }

    /**
     * Lists files in the sandbox file system.
     *
     * @param path sandbox directory path
     * @param shouldRecurse whether to list files recursively
     * @param maxDepth maximum recursion depth
     * @param sortBy field used to sort results
     * @param shouldSortDescending whether to sort in descending order
     * @param fileTypes file type filters
     * @param options additional sandbox-specific options
     * @return list-files result returned by the sandbox
     */
    public ListFilesResult listFiles(
            String path,
            boolean shouldRecurse,
            Integer maxDepth,
            String sortBy,
            boolean shouldSortDescending,
            List<String> fileTypes,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "listFiles", params(
                "path", path,
                "recursive", shouldRecurse,
                "maxDepth", maxDepth,
                "sortBy", sortBy,
                "sortDescending", shouldSortDescending,
                "fileTypes", fileTypes,
                "options", options), ListFilesResult.class);
    }

    /**
     * Lists directories in the sandbox file system.
     *
     * @param path sandbox directory path
     * @param shouldRecurse whether to list directories recursively
     * @param maxDepth maximum recursion depth
     * @param sortBy field used to sort results
     * @param shouldSortDescending whether to sort in descending order
     * @param options additional sandbox-specific options
     * @return list-directories result returned by the sandbox
     */
    public ListDirsResult listDirectories(
            String path,
            boolean shouldRecurse,
            Integer maxDepth,
            String sortBy,
            boolean shouldSortDescending,
            Map<String, Object> options) {
        return invoke(OP_TYPE, "listDirectories", params(
                "path", path,
                "recursive", shouldRecurse,
                "maxDepth", maxDepth,
                "sortBy", sortBy,
                "sortDescending", shouldSortDescending,
                "options", options), ListDirsResult.class);
    }

    /**
     * Searches for files in the sandbox file system.
     *
     * @param path sandbox directory path to search from
     * @param pattern file-name pattern to match
     * @param excludePatterns patterns to exclude from search results
     * @return search-files result returned by the sandbox
     */
    public SearchFilesResult searchFiles(String path, String pattern, List<String> excludePatterns) {
        return invoke(OP_TYPE, "searchFiles", params(
                "path", path,
                "pattern", pattern,
                "excludePatterns", excludePatterns), SearchFilesResult.class);
    }
}
