/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests sandbox example client against a local jiuwenbox-compatible mock service.
 *
 * @since 2026-06-29
 */
class SandboxExampleJiuwenBoxLocalServerTest {
    private static final String DECORATING_SANDBOX_CLIENT = "Created client: "
            + "com.openjiuwen.service.adapters.agentcore.external.DecoratingSandboxClient";

    @Test
    void sandboxAdapterExampleCanCallLocalJiuwenBoxCompatibleServer()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        Path classesDir = compileSandboxExample();
        String classpath = exampleClasspath(classesDir);

        try (JiuwenBoxMockServer server = JiuwenBoxMockServer.start()) {
            String serviceUrl = "http://127.0.0.1:" + server.port();
            String readFileOutput = runClient(
                    classpath,
                    "--url=" + serviceUrl,
                    "--retry-max=0",
                    "--operation=read-file",
                    "--path=/tmp/demo.txt");
            String shellOutput = runClient(
                    classpath,
                    "--url=" + serviceUrl,
                    "--retry-max=0",
                    "--operation=shell",
                    "--command=echo sandbox");
            String codeOutput = runClient(
                    classpath,
                    "--url=" + serviceUrl,
                    "--retry-max=0",
                    "--operation=code",
                    "--language=python",
                    "--code=print('sandbox')");

            assertThat(readFileOutput)
                    .contains(DECORATING_SANDBOX_CLIENT)
                    .contains("read-file content: mock jiuwenbox file:/tmp/demo.txt");
            assertThat(shellOutput)
                    .contains("shell exit code: 0")
                    .contains("shell stdout: mock jiuwenbox shell: echo sandbox");
            assertThat(codeOutput)
                    .contains("code exit code: 0")
                    .contains("code stdout: mock jiuwenbox code: python");
            assertThat(server.requests()).anySatisfy(request -> assertThat(request.path())
                    .isEqualTo("/api/v1/sandboxes"));
            assertThat(server.requests()).anySatisfy(request -> {
                assertThat(request.path()).isEqualTo("/api/v1/sandboxes/mock-sandbox/download");
                assertThat(request.query()).containsEntry("sandbox_path", "/tmp/demo.txt");
            });
            assertThat(server.requests()).anySatisfy(request -> {
                assertThat(request.path()).isEqualTo("/api/v1/sandboxes/mock-sandbox/exec");
                assertThat(request.commandText()).contains("echo sandbox");
            });
            assertThat(server.requests()).anySatisfy(request -> {
                assertThat(request.path()).isEqualTo("/api/v1/sandboxes/mock-sandbox/exec");
                assertThat(request.commandText()).contains("python3 -c");
            });
        }
    }

    private Path compileSandboxExample() throws IOException {
        Path adapterSource = Path.of("example/sandbox/SandboxAdapterExample.java");
        assertThat(adapterSource).exists();

        Path classesDir = Path.of("target/sandbox-example-test-classes");
        Files.createDirectories(classesDir);
        int exitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-cp",
                exampleDependencyClasspath(),
                "-d",
                classesDir.toString(),
                adapterSource.toString());
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

    private String runClient(String classpath, String... args)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-cp");
        command.add(classpath);
        command.add("com.openjiuwen.service.demo.example.sandbox.SandboxAdapterExample");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = outputFuture.get(5, TimeUnit.SECONDS);
        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }

    private String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static final class JiuwenBoxMockServer implements AutoCloseable {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final String SANDBOX_ID = "mock-sandbox";

        private final HttpServer server;
        private final ConcurrentLinkedQueue<RecordedRequest> requests = new ConcurrentLinkedQueue<>();

        private JiuwenBoxMockServer(HttpServer server) {
            this.server = server;
        }

        private static JiuwenBoxMockServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JiuwenBoxMockServer mockServer = new JiuwenBoxMockServer(server);
            server.createContext("/api/v1/sandboxes", mockServer::handleSandboxes);
            server.start();
            return mockServer;
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private List<RecordedRequest> requests() {
            return new ArrayList<>(requests);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handleSandboxes(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            Map<String, String> query = queryParameters(uri);
            Map<String, Object> body = readJsonBody(exchange);
            requests.add(new RecordedRequest(method, path, query, body));

            if ("POST".equals(method) && "/api/v1/sandboxes".equals(path)) {
                respondJson(exchange, 200, Map.of("id", SANDBOX_ID));
                return;
            }
            if ("GET".equals(method) && sandboxPath("/download").equals(path)) {
                respondBytes(exchange, 200, ("mock jiuwenbox file:" + query.get("sandbox_path"))
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("POST".equals(method) && sandboxPath("/exec").equals(path)) {
                respondJson(exchange, 200, execResponse(body));
                return;
            }
            if ("DELETE".equals(method) && sandboxPath("").equals(path)) {
                respondBytes(exchange, 204, new byte[0]);
                return;
            }
            respondJson(exchange, 404, Map.of("message", "sandbox route not found"));
        }

        private String sandboxPath(String suffix) {
            return "/api/v1/sandboxes/" + SANDBOX_ID + suffix;
        }

        private Map<String, Object> execResponse(Map<String, Object> body) {
            String commandText = commandText(body);
            if (commandText.contains("python3 -c")) {
                return Map.of("stdout", "mock jiuwenbox code: python", "stderr", "", "exit_code", 0);
            }
            return Map.of("stdout", "mock jiuwenbox shell: echo sandbox", "stderr", "", "exit_code", 0);
        }

        private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            if (bytes.length == 0) {
                return Map.of();
            }
            return MAPPER.readValue(bytes, new TypeReference<>() {
            });
        }

        private Map<String, String> queryParameters(URI uri) {
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isBlank()) {
                return Map.of();
            }
            return List.of(rawQuery.split("&")).stream()
                    .map(part -> part.split("=", 2))
                    .collect(Collectors.toMap(
                            part -> decode(part[0]),
                            part -> part.length > 1 ? decode(part[1]) : ""));
        }

        private String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private void respondJson(HttpExchange exchange, int status, Object response) throws IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respondBytes(exchange, status, bytes);
        }

        private void respondBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private record RecordedRequest(
            String method,
            String path,
            Map<String, String> query,
            Map<String, Object> body) {
        private String commandText() {
            return SandboxExampleJiuwenBoxLocalServerTest.commandText(body);
        }
    }

    private static String commandText(Map<String, Object> body) {
        Object command = body.get("command");
        if (command instanceof List<?> commandList) {
            return commandList.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(" "));
        }
        return "";
    }
}
