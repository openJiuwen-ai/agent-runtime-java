package com.openjiuwen.a2a_service.agents.EDPAgent.api;

import com.openjiuwen.a2a_service.agents.EDPAgent.agent.EDPAgentRuntime;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.EdpAgentSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class EDPAgentServerMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(EDPAgentServerMain.class);

    private EDPAgentServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        EdpAgentSettings settings = EdpAgentSettings.load(parsed.configPath, System.getenv());

        EDPAgentRuntime runtime = new EDPAgentRuntime(new com.openjiuwen.a2a_service.agents.EDPAgent.agent.EDPAgentFactory(new com.openjiuwen.a2a_service.agents.EDPAgent.config.AgentRuleLoader(), settings));
        runtime.initialize(parsed.rulePath);

        String host = parsed.host != null ? parsed.host : settings.getFastApiHost();
        int port = parsed.port != null ? parsed.port : settings.getFastApiPort();

        NorthboundApiServer server = new NorthboundApiServer(runtime, host, port);
        server.start();
        LOGGER.info("EDPAgent northbound API started on http://{}:{}", host, server.getPort());
    }

    private static final class Arguments {
        private final String host;
        private final Integer port;
        private final Path rulePath;
        private final Path configPath;

        private Arguments(String host, Integer port, Path rulePath, Path configPath) {
            this.host = host;
            this.port = port;
            this.rulePath = rulePath;
            this.configPath = configPath;
        }

        private static Arguments parse(String[] args) {
            String host = null;
            Integer port = null;
            Path rulePath = Path.of("AgentRule.md");
            Path configPath = Path.of("config", "edp-agent.json");

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--host".equals(arg)) {
                    host = requireValue(args, ++i, "--host");
                    continue;
                }
                if ("--port".equals(arg)) {
                    port = parsePort(requireValue(args, ++i, "--port"));
                    continue;
                }
                if ("--rule".equals(arg)) {
                    rulePath = Path.of(requireValue(args, ++i, "--rule"));
                    continue;
                }
                if ("--config".equals(arg)) {
                    configPath = Path.of(requireValue(args, ++i, "--config"));
                    continue;
                }
                if (arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
                if (isInteger(arg)) {
                    port = parsePort(arg);
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        rulePath = Path.of(args[++i]);
                    }
                    continue;
                }
                rulePath = Path.of(arg);
            }

            return new Arguments(host, port, rulePath, configPath);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        private static boolean isInteger(String value) {
            try {
                Integer.parseInt(value);
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    }

    private static int parsePort(String rawPort) {
        try {
            return Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            return 8090;
        }
    }
}
