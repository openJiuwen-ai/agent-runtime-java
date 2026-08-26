/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.security.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates ephemeral PKCS12 material for TLS / mTLS integration tests via {@code keytool}.
 *
 * <p>Files are created under a temporary directory and are not committed to the repository.
 *
 * @since 0.1.0
 */
public final class TlsTestCertificates {
    /** Password for all generated test keystores. Not a production secret — protects ephemeral self-signed certificates created at runtime. */
    public static final String PASSWORD = "testpass";

    private TlsTestCertificates() {
    }

    /**
     * Generated TLS material for integration tests.
     *
     * @param directory working directory containing generated stores
     * @param serverKeyStore server identity keystore
     * @param serverTrustStore server truststore (contains client certificate)
     * @param clientKeyStore client identity keystore
     * @param clientTrustStore client truststore (contains server certificate)
     */
    public record Material(Path directory, Path serverKeyStore, Path serverTrustStore, Path clientKeyStore,
        Path clientTrustStore) {
        /**
         * Returns a Spring {@code file:} location for the server keystore.
         *
         * @return location string
         */
        public String serverKeyStoreLocation() {
            return toFileLocation(serverKeyStore);
        }

        /**
         * Returns a Spring {@code file:} location for the server truststore.
         *
         * @return location string
         */
        public String serverTrustStoreLocation() {
            return toFileLocation(serverTrustStore);
        }

        /**
         * Returns a Spring {@code file:} location for the client keystore.
         *
         * @return location string
         */
        public String clientKeyStoreLocation() {
            return toFileLocation(clientKeyStore);
        }

        /**
         * Returns a Spring {@code file:} location for the client truststore.
         *
         * @return location string
         */
        public String clientTrustStoreLocation() {
            return toFileLocation(clientTrustStore);
        }

        private static String toFileLocation(Path path) {
            return path.toUri().toString();
        }
    }

    /**
     * Generates server/client keystores and truststores for localhost TLS tests.
     *
     * @return generated material
     * @throws IOException if keytool execution fails
     * @throws InterruptedException if keytool is interrupted
     */
    public static Material generate() throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("agent-service-tls-it-");
        Path serverKeyStore = directory.resolve("server.p12");

        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", serverKeyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD, "-validity", "1",
            "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1");
        Path clientKeyStore = directory.resolve("client.p12");
        runKeytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", clientKeyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD, "-validity", "1",
            "-dname", "CN=test-client");

        Path serverCert = directory.resolve("server.crt");
        runKeytool("-exportcert", "-alias", "server", "-keystore", serverKeyStore.toString(), "-storepass", PASSWORD,
            "-file", serverCert.toString());
        Path clientCert = directory.resolve("client.crt");
        runKeytool("-exportcert", "-alias", "client", "-keystore", clientKeyStore.toString(), "-storepass", PASSWORD,
            "-file", clientCert.toString());

        Path clientTrustStore = directory.resolve("client-trust.p12");
        runKeytool("-importcert", "-alias", "server", "-file", serverCert.toString(), "-keystore",
            clientTrustStore.toString(), "-storepass", PASSWORD, "-storetype", "PKCS12", "-noprompt");
        Path serverTrustStore = directory.resolve("server-trust.p12");
        runKeytool("-importcert", "-alias", "client", "-file", clientCert.toString(), "-keystore",
            serverTrustStore.toString(), "-storepass", PASSWORD, "-storetype", "PKCS12", "-noprompt");

        return new Material(directory, serverKeyStore, serverTrustStore, clientKeyStore, clientTrustStore);
    }

    /**
     * Generates a server keystore whose certificate has already expired.
     *
     * @param directory working directory for the generated store
     * @return path to the expired server keystore
     * @throws IOException if keytool execution fails
     * @throws InterruptedException if keytool is interrupted
     */
    public static Path generateExpiredServerKeyStore(Path directory) throws IOException, InterruptedException {
        Path expiredServerKeyStore = directory.resolve("expired-server.p12");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", expiredServerKeyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD,
            "-startdate", "2020/01/01", "-validity", "1", "-dname", "CN=localhost", "-ext",
            "SAN=DNS:localhost,IP:127.0.0.1");
        return expiredServerKeyStore;
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
            throw new IllegalStateException(
                "keytool failed with exit code " + exitCode + ": " + output.trim());
        }
    }
}
