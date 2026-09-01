/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates ephemeral PKCS12 material for outbound TLS unit tests.
 */
final class OutboundTlsTestCertificates {
    /** Password for generated test keystores. Not a production secret — protects ephemeral self-signed certificates created at runtime. */
    static final String PASSWORD = "testpass";

    private OutboundTlsTestCertificates() {
    }

    static Path generateServerKeyStore() throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("adapters-common-tls-ut-");
        Path serverKeyStore = directory.resolve("server.p12");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", serverKeyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD, "-validity", "1",
            "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1");
        return serverKeyStore;
    }

    private static void runKeytool(String... args) throws IOException, InterruptedException {
        List<String> runToolCommands = new ArrayList<>();
        runToolCommands.add(Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString());
        for (String arg : args) {
            runToolCommands.add(arg);
        }
        Process runToolprocess = new ProcessBuilder(runToolCommands).redirectErrorStream(true).start();
        String procOutput = new String(runToolprocess.getInputStream().readAllBytes());
        int exitCode = runToolprocess.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("keytool failed with exit code " + exitCode + ": " + procOutput.trim());
        }
    }
}
