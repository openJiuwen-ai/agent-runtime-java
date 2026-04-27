package com.openjiuwen.a2a_service.agents.EDPAgent;

import com.openjiuwen.a2a_service.agents.EDPAgent.agent.EDPAgentFacade;
import com.openjiuwen.a2a_service.agents.EDPAgent.agent.EDPAgentFactory;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.AgentRuleLoader;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.EdpAgentSettings;
import com.openjiuwen.a2a_service.common.Events;
import com.openjiuwen.a2a_service.config.DPASettings;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Agent {

    private static final Logger LOGGER = LoggerFactory.getLogger(Agent.class);
    private static final String RESOURCE_ROOT = "agents/EDPAgent";
    private static final String[] SKILL_FILES = {
            "model_driven_fund_planning_skill/SKILL.md",
            "rebuild_interact_finance_rec_skill/SKILL.md",
            "rebuild_product_recommend_skill/SKILL.md",
            "rebuild_product_select_skill/SKILL.md"
    };

    private static volatile EDPAgentFacade facade;

    private Agent() {
    }

    public static synchronized void initializeDpa(DPASettings settings) {
        if (facade != null) {
            return;
        }
        try {
            configureRunner(settings);
            Path agentRoot = extractBundledAgent();
            EdpAgentSettings edpSettings = toEdpSettings(settings);
            EDPAgentFactory factory = new EDPAgentFactory(new AgentRuleLoader(), edpSettings);
            facade = new EDPAgentFacade(agentRoot.resolve("AgentRule.md"), factory);
            facade.initialize();
            LOGGER.info("[DPA] EDPAgent initialized from {}", agentRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize EDPAgent", e);
        }
    }

    public static List<Object> agentStream(
            String query,
            String convId,
            Map<String, Object> cascadeResult,
            Map<String, Object> context
    ) {
        EDPAgentFacade currentFacade = getFacade();
        List<Object> events = new ArrayList<Object>();
        try {
            Iterator<Map<String, Object>> iterator = currentFacade.agentStream(query, convId, cascadeResult, context);
            while (iterator.hasNext()) {
                Object converted = convertEvent(iterator.next());
                if (converted != null) {
                    events.add(converted);
                }
            }
        } catch (IOException e) {
            LOGGER.error("[DPA] EDPAgent stream failed", e);
            events.add(Map.of("type", "error", "content", e.getMessage() != null ? e.getMessage() : "EDPAgent stream failed"));
        }
        return events;
    }

    private static EDPAgentFacade getFacade() {
        EDPAgentFacade currentFacade = facade;
        if (currentFacade == null) {
            throw new IllegalStateException("DPA is not initialized, call initializeDpa first");
        }
        return currentFacade;
    }

    private static Object convertEvent(Map<String, Object> event) {
        String type = String.valueOf(event.getOrDefault("type", ""));
        if ("delegate_request".equals(type)) {
            Map<String, Object> data = castMap(event.get("data"));
            return new Events.DelegateRequest(
                    String.valueOf(data.getOrDefault("intent", "")),
                    stringOrNull(data.get("target_agent")),
                    String.valueOf(data.getOrDefault("task_description", ""))
            );
        }
        return event;
    }

    private static void configureRunner(DPASettings settings) {
        DPASettings effectiveSettings = settings != null ? settings : new DPASettings();
        RunnerConfig runnerConfig = RunnerConfig.builder()
                .distributedMode(false)
                .checkpointerConfig(Map.of(
                        "type", "redis",
                        "conf", Map.of(
                                "connection", Map.of("url", effectiveSettings.getRedisUrl()),
                                "ttl", Map.of(
                                        "default_ttl", effectiveSettings.getRedisCheckpointerTtlMinutes(),
                                        "refresh_on_read", true
                                )
                        )
                ))
                .build();
        Runner.setConfig(runnerConfig);
        Runner.start();
    }

    private static EdpAgentSettings toEdpSettings(DPASettings settings) {
        DPASettings effectiveSettings = settings != null ? settings : new DPASettings();
        return new EdpAgentSettings(
                "0.0.0.0",
                8090,
                value(effectiveSettings.getLlmModelName()),
                value(effectiveSettings.getLlmApiKey()),
                value(effectiveSettings.getLlmApiBase()),
                effectiveSettings.getLlmTimeout(),
                effectiveSettings.isLlmVerifySsl(),
                value(effectiveSettings.getLlmToken()),
                value(effectiveSettings.getLlmTokenHeader()),
                value(effectiveSettings.getLlmUserId()),
                value(effectiveSettings.getLlmUserIdHeader()),
                effectiveSettings.getLlmExtraHeaders(),
                "",
                600.0d
        );
    }

    private static Path extractBundledAgent() throws IOException {
        Path root = Files.createTempDirectory("runtime-edp-agent-");
        copyResource(RESOURCE_ROOT + "/AgentRule.md", root.resolve("AgentRule.md"));
        for (String skillFile : SKILL_FILES) {
            copyResource(
                    RESOURCE_ROOT + "/skills/" + skillFile,
                    root.resolve("skills").resolve(skillFile)
            );
        }
        return root;
    }

    private static void copyResource(String resourcePath, Path target) throws IOException {
        ClassLoader classLoader = Agent.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing resource: " + resourcePath);
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target);
        }
    }

    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private static String value(String value) {
        return value != null ? value : "";
    }

    private static String stringOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
