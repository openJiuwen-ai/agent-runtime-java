package com.openjiuwen.a2a_service.agents.EDPAgent.agent;

import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperationCard;
import com.openjiuwen.core.sysop.config.LocalWorkConfig;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.AgentRuleConfig;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.AgentRuleDocument;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.AgentRuleLoader;
import com.openjiuwen.a2a_service.agents.EDPAgent.config.EdpAgentSettings;
import com.openjiuwen.a2a_service.agents.EDPAgent.rail.ExecutionLimitRail;
import com.openjiuwen.a2a_service.agents.EDPAgent.rail.IterationLimitRail;
import com.openjiuwen.a2a_service.agents.EDPAgent.rail.MCPInterruptRail;
import com.openjiuwen.a2a_service.agents.EDPAgent.rail.RedisInterruptRail;
import com.openjiuwen.a2a_service.agents.EDPAgent.rail.VersatileInterruptRail;
import com.openjiuwen.a2a_service.agents.EDPAgent.tool.EDPTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class EDPAgentFactory {

    public static final String DEFAULT_AGENT_ID = "edp_agent";
    public static final String SYS_OPERATION_ID = "edp_agent_sysop";
    public static final String BUSINESS_RULES_SECTION = "business_rules";
    public static final int BUSINESS_RULES_PRIORITY = 20;
    public static final String PROMPT_SUPPLEMENT_SECTION = "prompt_supplement";
    public static final int PROMPT_SUPPLEMENT_PRIORITY = 30;

    private final AgentRuleLoader agentRuleLoader;
    private final EdpAgentSettings settings;
    private Path agentRoot = Paths.get(".").toAbsolutePath().normalize();

    public EDPAgentFactory() {
        this(new AgentRuleLoader(), EdpAgentSettings.load());
    }

    public EDPAgentFactory(AgentRuleLoader agentRuleLoader) {
        this(agentRuleLoader, EdpAgentSettings.load());
    }

    public EDPAgentFactory(AgentRuleLoader agentRuleLoader, EdpAgentSettings settings) {
        this.agentRuleLoader = agentRuleLoader;
        this.settings = settings != null ? settings : new EdpAgentSettings();
    }

    public ReActAgent createDefault() throws IOException {
        return create(Paths.get("AgentRule.md"));
    }

    public ReActAgent create(Path agentRulePath) throws IOException {
        Path normalizedRulePath = agentRulePath.toAbsolutePath().normalize();
        Path parent = normalizedRulePath.getParent();
        if (parent != null) {
            agentRoot = parent;
        }
        AgentRuleDocument document = agentRuleLoader.load(normalizedRulePath);
        return create(document);
    }

    public ReActAgent create(AgentRuleDocument document) {
        AgentRuleConfig config = document.getConfig();
        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id(DEFAULT_AGENT_ID)
                .name("EDPAgent")
                .description("Dynamic Planning ReAct Agent")
                .build());

        ReActAgentConfig reactAgentConfig = ReActAgentConfig.builder()
                .maxIterations(config.getLimits().getMaxIterations())
                .build();
        reactAgentConfig.setSysOperationId(SYS_OPERATION_ID);
        configureLlmFromSettings(reactAgentConfig, settings);
        agent.configure(reactAgentConfig);
        agent.addPromptBuilderSection(BUSINESS_RULES_SECTION, document.getPromptBody(), BUSINESS_RULES_PRIORITY);
        agent.addPromptBuilderSection(
                PROMPT_SUPPLEMENT_SECTION,
                EDPAgentPromptSupport.buildSystemPrompt(),
                PROMPT_SUPPLEMENT_PRIORITY
        );

        registerSysOperations(agent);
        agent.registerRail(new IterationLimitRail(config));
        agent.registerRail(new ExecutionLimitRail(config));
        agent.registerRail(new MCPInterruptRail());
        agent.registerRail(new RedisInterruptRail());
        agent.registerRail(new VersatileInterruptRail(SYS_OPERATION_ID));
        registerTools(agent);
        registerSkills(agent);
        return agent;
    }

    private void registerSysOperations(ReActAgent agent) {
        Runner.resourceMgr().removeSysOperation(SYS_OPERATION_ID, agent.getCard().getId(), TagMatchStrategy.ALL, true);
        SysOperationCard sysOperationCard = SysOperationCard.builder()
                .id(SYS_OPERATION_ID)
                .name("edp-agent-sysop")
                .description("EDPAgent sys operations")
                .mode(OperationMode.LOCAL)
                .workConfig(LocalWorkConfig.builder()
                        .workDir(agentRoot.toString())
                        .shellAllowlist(java.util.Arrays.asList(
                                "echo", "ls", "dir", "cd", "pwd",
                                "python", "python3", "pip", "pip3",
                                "npm", "node", "git", "cat", "type",
                                "bash", "sh", "mkdir", "md", "rm", "rd",
                                "cp", "copy", "mv", "move", "grep", "find",
                                "curl", "wget", "ps", "df", "ping"
                        ))
                        .build())
                .build();
        Runner.resourceMgr().addSysOperation(sysOperationCard, agent.getCard().getId());

        Object readFileCard = Runner.resourceMgr().getSysOpToolCards(SYS_OPERATION_ID, "fs", "readFile");
        if (readFileCard != null) {
            agent.getAbilityManager().add(readFileCard);
        }

        Object executeCmdCard = Runner.resourceMgr().getSysOpToolCards(SYS_OPERATION_ID, "shell", "executeCmd");
        if (executeCmdCard != null) {
            agent.getAbilityManager().add(executeCmdCard);
        }
    }

    private void registerTools(ReActAgent agent) {
        for (Tool tool : EDPTools.createAll()) {
            Runner.resourceMgr().removeTool(tool.getCard().getId(), agent.getCard().getId(), TagMatchStrategy.ALL, true);
            Runner.resourceMgr().addTool(tool, agent.getCard().getId());
            agent.getAbilityManager().add(tool.getCard());
        }
    }

    private void registerSkills(ReActAgent agent) {
        Path skillsRoot = agentRoot.resolve("skills");
        if (Files.exists(skillsRoot)) {
            try (java.util.stream.Stream<Path> paths = Files.list(skillsRoot)) {
                paths.filter(Files::isDirectory)
                        .filter(path -> Files.exists(path.resolve("SKILL.md")))
                        .sorted()
                        .forEach(path -> agent.registerSkill(path.toAbsolutePath().normalize().toString()));
            } catch (IOException ignored) {
            }
        }
    }

    void configureLlmFromEnv(ReActAgentConfig config, Map<String, String> env) {
        configureLlmFromSettings(config, EdpAgentSettings.fromMap(env));
    }

    void configureLlmFromSettings(ReActAgentConfig config, EdpAgentSettings settings) {
        String modelName = settings.getPlanningAgentModelName();
        String apiKey = settings.getPlanningAgentModelApiKey();
        String apiBase = settings.getPlanningAgentModelBaseUrl();
        String token = settings.getPlanningAgentModelToken();
        String tokenHeader = settings.getPlanningAgentModelTokenHeader();
        String userId = settings.getPlanningAgentModelUserId();
        String userIdHeader = settings.getPlanningAgentModelUserIdHeader();
        boolean verifySsl = settings.isSkillLlmTlsVerify();
        double timeout = settings.getPlanningAgentModelTimeout();

        if (apiKey.isBlank()) {
            return;
        }

        String provider = detectProvider(apiBase);
        Map<String, String> customHeaders = buildCustomHeaders(
                token,
                tokenHeader,
                userId,
                userIdHeader,
                settings.getPlanningAgentModelExtraHeaders()
        );
        if (!customHeaders.isEmpty()) {
            config.configureCustomHeaders(customHeaders);
        }
        config.configureModelClient(provider, apiKey, apiBase, modelName, verifySsl);

        if (config.getModelClientConfig() != null) {
            ModelClientConfig current = config.getModelClientConfig();
            Map<String, String> headers = current.getHeaders() != null
                    ? new LinkedHashMap<String, String>(current.getHeaders())
                    : null;
            config.setModelClientConfig(ModelClientConfig.builder()
                    .clientId(current.getClientId())
                    .clientProvider(current.getClientProvider())
                    .apiKey(current.getApiKey())
                    .apiBase(current.getApiBase())
                    .timeout(timeout)
                    .maxRetries(current.getMaxRetries())
                    .verifySsl(current.isVerifySsl())
                    .sslCert(current.getSslCert())
                    .headers(headers)
                    .build());
        }
    }

    private Map<String, String> buildCustomHeaders(
            String token,
            String tokenHeader,
            String userId,
            String userIdHeader,
            Map<String, String> extraHeaders
    ) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (!token.isBlank()) {
            if (tokenHeader.isBlank()) {
                throw new IllegalArgumentException(
                        "PLANNING_AGENT_MODEL_TOKEN is set but PLANNING_AGENT_MODEL_TOKEN_HEADER is missing");
            }
            headers.put(tokenHeader, token);
        }
        if (!userId.isBlank()) {
            if (userIdHeader.isBlank()) {
                throw new IllegalArgumentException(
                        "PLANNING_AGENT_MODEL_USER_ID is set but PLANNING_AGENT_MODEL_USER_ID_HEADER is missing");
            }
            headers.put(userIdHeader, userId);
        }
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        return headers;
    }

    private String detectProvider(String apiBase) {
        String normalized = apiBase != null ? apiBase.toLowerCase(Locale.ROOT) : "";
        if (normalized.contains("dashscope") || normalized.contains("aliyun")) {
            return "DashScope";
        }
        if (normalized.contains("siliconflow")) {
            return "SiliconFlow";
        }
        return "OpenAI";
    }
}
