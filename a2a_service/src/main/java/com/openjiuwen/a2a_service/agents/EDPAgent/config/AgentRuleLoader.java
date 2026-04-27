package com.openjiuwen.a2a_service.agents.EDPAgent.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentRuleLoader {

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*\\R?(.*)$", Pattern.DOTALL);

    public AgentRuleDocument load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new IOException("AgentRule file not found: " + path);
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return parse(content);
    }

    public AgentRuleDocument parse(String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content != null ? content : "");
        if (!matcher.matches()) {
            throw new IllegalArgumentException("No YAML frontmatter found in AgentRule.md");
        }

        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = yaml.loadAs(matcher.group(1), LinkedHashMap.class);
        AgentRuleConfig config = buildConfig(root != null ? root : Map.<String, Object>of());
        return new AgentRuleDocument(config, matcher.group(2).trim());
    }

    private AgentRuleConfig buildConfig(Map<String, Object> root) {
        AgentRuleConfig config = new AgentRuleConfig();

        config.setScope(buildScope(castMap(root.get("scope"))));
        config.setPlanningSteps(castStringList(root.get("planning_steps")));
        config.setLimits(buildLimits(castMap(root.get("limits"))));
        config.setSummary(buildSummary(castMap(root.get("summary"))));
        config.setScripts(castStringMap(root.get("scripts")));

        validate(config);
        return config;
    }

    private ScopeConfig buildScope(Map<String, Object> data) {
        ScopeConfig scope = new ScopeConfig();
        scope.setAllowed(asString(data.get("allowed"), ""));
        scope.setOutOfScopeMessage(asString(data.get("out_of_scope_message"), scope.getOutOfScopeMessage()));
        return scope;
    }

    private LimitsConfig buildLimits(Map<String, Object> data) {
        LimitsConfig limits = new LimitsConfig();
        limits.setMaxIterations(asInt(data.get("max_iterations"), limits.getMaxIterations()));
        limits.setMaxInputAttempts(asInt(data.get("max_input_attempts"), limits.getMaxInputAttempts()));
        limits.setInterruptTimeoutSeconds(asInt(data.get("interrupt_timeout_seconds"), limits.getInterruptTimeoutSeconds()));
        limits.setTasks(castIntMap(data.get("tasks")));
        List<String> terminationKeywords = castStringList(data.get("termination_keywords"));
        if (!terminationKeywords.isEmpty()) {
            limits.setTerminationKeywords(terminationKeywords);
        }
        return limits;
    }

    private SummaryConfig buildSummary(Map<String, Object> data) {
        SummaryConfig summary = new SummaryConfig();
        summary.setFormat(asString(data.get("format"), summary.getFormat()));
        summary.setMaxLength(asInt(data.get("max_length"), summary.getMaxLength()));
        summary.setRequiredFields(castStringList(data.get("required_fields")));
        return summary;
    }

    private void validate(AgentRuleConfig config) {
        if (config.getScope().getAllowed() == null || config.getScope().getAllowed().isBlank()) {
            throw new IllegalArgumentException("scope.allowed is required");
        }
        if (config.getPlanningSteps().isEmpty()) {
            throw new IllegalArgumentException("planning_steps must not be empty");
        }
        if (config.getLimits().getMaxIterations() < 1) {
            throw new IllegalArgumentException("limits.max_iterations must be >= 1");
        }
        if (config.getLimits().getMaxInputAttempts() < 1) {
            throw new IllegalArgumentException("limits.max_input_attempts must be >= 1");
        }
        for (Map.Entry<String, Integer> entry : config.getLimits().getTasks().entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("Task limit for '" + entry.getKey() + "' must be >= 1");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<String, Object>();
    }

    private Map<String, String> castStringMap(Object value) {
        Map<String, Object> raw = castMap(value);
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), asString(entry.getValue(), ""));
        }
        return result;
    }

    private Map<String, Integer> castIntMap(Object value) {
        Map<String, Object> raw = castMap(value);
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), asInt(entry.getValue(), 0));
        }
        return result;
    }

    private List<String> castStringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private String asString(Object value, String defaultValue) {
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
