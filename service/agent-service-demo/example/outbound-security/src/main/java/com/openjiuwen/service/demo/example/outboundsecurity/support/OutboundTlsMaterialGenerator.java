/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.outboundsecurity.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Generates ephemeral PKCS12 material for outbound-security demos via {@code keytool}.
 *
 * <p>Files are created under a temporary directory and must not be committed. The store password is
 * generated randomly on every invocation and returned in {@link Material#password()}; it is never
 * persisted and never reused across runs.
 *
 * @since 0.1.0
 */
public final class OutboundTlsMaterialGenerator {
    private static final int PASSWORD_ENTROPY_BYTES = 24;

    private OutboundTlsMaterialGenerator() {
    }

    /**
     * Generated TLS material for outbound demos.
     *
     * @param directory working directory containing generated stores
     * @param serverKeyStore server identity keystore
     * @param clientTrustStore client truststore (contains server certificate)
     * @param clientKeyStore client identity keystore (optional mTLS)
     * @param password randomly generated password protecting all stores above
     */
    public record Material(Path directory, Path serverKeyStore, Path clientTrustStore, Path clientKeyStore,
        String password) {
        /**
         * Returns a Spring {@code file:} location for the server keystore.
         *
         * @return location string
         */
        public String serverKeyStoreLocation() {
            return serverKeyStore.toUri().toString();
        }

        /**
         * Returns a Spring {@code file:} location for the client truststore.
         *
         * @return location string
         */
        public String clientTrustStoreLocation() {
            return clientTrustStore.toUri().toString();
        }
    }

    /**
     * Generates server/client keystores for localhost HTTPS outbound demos.
     *
     * @return generated material
     * @throws IOException if keytool execution fails
     * @throws InterruptedException if keytool is interrupted
     */
    public static Material generate() throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("agent-outbound-security-demo-");
        String storePass = randomStorePass();
        Path serverKeyStore = directory.resolve("server.p12");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", serverKeyStore.toString(), "-storepass", storePass, "-keypass", storePass, "-validity", "1",
            "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1");

        Path clientKeyStore = directory.resolve("client.p12");
        runKeytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", clientKeyStore.toString(), "-storepass", storePass, "-keypass", storePass, "-validity", "1",
            "-dname", "CN=demo-client");

        Path serverCert = directory.resolve("server.crt");
        runKeytool("-exportcert", "-alias", "server", "-keystore", serverKeyStore.toString(), "-storepass", storePass,
            "-file", serverCert.toString());

        Path clientTrustStore = directory.resolve("client-trust.p12");
        runKeytool("-importcert", "-alias", "server", "-file", serverCert.toString(), "-keystore",
            clientTrustStore.toString(), "-storepass", storePass, "-storetype", "PKCS12", "-noprompt");

        return new Material(directory, serverKeyStore, clientTrustStore, clientKeyStore, storePass);
    }

    private static String randomStorePass() {
        byte[] entropy = new byte[PASSWORD_ENTROPY_BYTES];
        new SecureRandom().nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    private static void runKeytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString());
        for (String arg : args) {
            command.add(arg);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("keytool failed with exit code " + exitCode + ": " + output.trim());
        }
    }
}
