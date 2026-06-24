/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests sandbox example client and mock server running together locally.
 *
 * @since 2026-06-24
 */
class SandboxExampleLocalServerTest {
    @Test
    void sandboxAdapterExampleCanCallLocalMockSandboxServer() throws Exception {
        Path classesDir = compileSandboxExamples();
        int port = freePort();
        String classpath = exampleClasspath(classesDir);
        Process server = new ProcessBuilder(
                javaCommand(),
                "-cp",
                classpath,
                "com.openjiuwen.service.demo.example.sandbox.MockSandboxServerExample",
                "--port=" + port)
                .redirectErrorStream(true)
                .start();

        try {
            waitForServer(server, port);

            String readFileOutput = runClient(classpath,
                    "--url=http://127.0.0.1:" + port,
                    "--retry-max=0",
                    "--operation=read-file",
                    "--path=/tmp/demo.txt");
            assertThat(readFileOutput).contains("read-file content: mock sandbox file:/tmp/demo.txt");

            String shellOutput = runClient(classpath,
                    "--url=http://127.0.0.1:" + port,
                    "--retry-max=0",
                    "--operation=shell",
                    "--command=echo sandbox");
            assertThat(shellOutput).contains("shell exit code: 0");
            assertThat(shellOutput).contains("shell stdout: mock shell executed: echo sandbox");

            String codeOutput = runClient(classpath,
                    "--url=http://127.0.0.1:" + port,
                    "--retry-max=0",
                    "--operation=code",
                    "--language=python",
                    "--code=print('sandbox')");
            assertThat(codeOutput).contains("code exit code: 0");
            assertThat(codeOutput).contains("code stdout: mock code executed: python:print('sandbox')");
        } finally {
            server.destroy();
            if (!server.waitFor(3, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    private Path compileSandboxExamples() throws IOException {
        Path adapterSource = Path.of("example/sandbox/SandboxAdapterExample.java");
        Path serverSource = Path.of("example/sandbox/MockSandboxServerExample.java");
        assertThat(adapterSource).exists();
        assertThat(serverSource).exists();

        Path classesDir = Path.of("target/example-test-classes");
        Files.createDirectories(classesDir);
        int exitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-cp",
                exampleDependencyClasspath(),
                "-d",
                classesDir.toString(),
                adapterSource.toString(),
                serverSource.toString());
        assertThat(exitCode).isZero();
        return classesDir;
    }

    private String exampleClasspath(Path classesDir) {
        String separator = System.getProperty("path.separator");
        List<String> classpath = new ArrayList<>();
        classpath.add(classesDir.toString());
        addLocalAdapterClasses(classpath);
        classpath.add(System.getProperty("java.class.path"));
        return String.join(separator, classpath);
    }

    private String exampleDependencyClasspath() {
        String separator = System.getProperty("path.separator");
        List<String> classpath = new ArrayList<>();
        addLocalAdapterClasses(classpath);
        classpath.add(System.getProperty("java.class.path"));
        return String.join(separator, classpath);
    }

    private void addLocalAdapterClasses(List<String> classpath) {
        addIfDirectory(classpath, "../agent-service-adapters/agent-service-adapters-agentcore/target/classes");
        addIfDirectory(classpath, "../agent-service-adapters/agent-service-adapters-common/target/classes");
    }

    private void addIfDirectory(List<String> classpath, String path) {
        Path localClasses = Path.of(path);
        if (Files.isDirectory(localClasses)) {
            classpath.add(localClasses.toString());
        }
    }

    private String runClient(String classpath, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-cp");
        command.add(classpath);
        command.add("com.openjiuwen.service.demo.example.sandbox.SandboxAdapterExample");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }

    private void waitForServer(Process server, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
            while (System.nanoTime() < deadline) {
                if (!server.isAlive()) {
                    throw new AssertionError("mock sandbox server exited early with code " + server.exitValue()
                            + System.lineSeparator() + readAvailable(reader));
                }
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && line.contains("http://127.0.0.1:" + port + "/invoke")) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("mock sandbox server did not start on port " + port);
    }

    private String readAvailable(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        while (reader.ready()) {
            output.append(reader.readLine()).append(System.lineSeparator());
        }
        return output.toString();
    }

    private int freePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    private String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
