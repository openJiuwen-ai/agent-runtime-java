/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.runbudget.RunBudgetConfig;
import com.openjiuwen.core.singleagent.runbudget.RunBudgetRail;
import com.openjiuwen.service.app.config.runbudget.RunBudgetProperties;
import com.openjiuwen.service.app.config.runbudget.RunBudgetWiring;

import java.util.Optional;

/**
 * FEAT-057 run-budget wiring between {@link RunBudgetProperties} and the agent-core budget
 * rail. All gating rules live here: an explicit legacy {@code max-iterations} always wins over
 * the elastic turn budget, zero/absent optional values mean "not declared", and illegal values
 * fail fast with {@link IllegalStateException} at wiring time.
 *
 * @since 2026-10-08
 */
final class RunBudgetWirer {
    private static final int HARD_LIMIT_FLOOR = 200;

    private RunBudgetWirer() {
    }

    /**
     * Resolves the budget config from raw properties, applying gating rules.
     *
     * @param wiring wiring inputs, may be null
     * @return the budget config, or empty when neither dimension is enabled
     */
    static Optional<RunBudgetConfig> resolveBudgetConfig(RunBudgetWiring wiring) {
        if (wiring == null || wiring.budgetProperties() == null) {
            return Optional.empty();
        }
        RunBudgetProperties properties = wiring.budgetProperties();
        boolean turnEnabled = properties.getTurn().isEnabled() && rawMaxIterationsAbsent(wiring);
        boolean timeEnabled = normalizeZero(properties.getTime().getTotalBudgetSeconds()) != null;
        if (!turnEnabled && !timeEnabled) {
            return Optional.empty();
        }
        return Optional.of(toRunBudgetConfig(properties, turnEnabled, timeEnabled));
    }

    /**
     * Returns the fallback loop envelope for an enabled turn dimension
     * ({@code hardLimitMax + 1}, reserving one round for the final answer).
     *
     * @param config budget config with the turn dimension enabled
     * @return loop upper bound for {@code maxIterations}
     */
    static int fallbackMaxIterations(RunBudgetConfig config) {
        return hardLimitMax(config) + 1;
    }

    /**
     * Registers the budget rail on the agent.
     *
     * @param agent the agent under construction
     * @param config resolved budget config
     */
    static void registerRail(ReActAgent agent, RunBudgetConfig config) {
        agent.registerRail(new RunBudgetRail(config)).toCompletableFuture().join();
    }

    private static boolean rawMaxIterationsAbsent(RunBudgetWiring wiring) {
        return wiring.llmProperties() != null && wiring.llmProperties().getMaxIterations() == null;
    }

    private static int hardLimitMax(RunBudgetConfig config) {
        if (config.getHardLimit() != null) {
            return config.getHardLimit();
        }
        int guarantee = config.getSuggestedRounds() != null
                ? config.getSuggestedRounds() : config.getDefaultGuaranteedRounds();
        return Math.max(guarantee * 2, HARD_LIMIT_FLOOR);
    }

    private static RunBudgetConfig toRunBudgetConfig(RunBudgetProperties properties, boolean turnEnabled,
        boolean timeEnabled) {
        RunBudgetProperties.Turn turn = properties.getTurn();
        RunBudgetProperties.Checkpoint checkpoint = turn.getCheckpoint();
        RunBudgetProperties.Time time = properties.getTime();
        try {
            return RunBudgetConfig.builder()
                .turnEnabled(turnEnabled)
                .suggestedRounds(normalizeZero(turn.getSuggestedRounds()))
                .defaultGuaranteedRounds(turn.getDefaultGuaranteedRounds())
                .hardLimit(normalizeZero(turn.getHardLimit()))
                .firstCheckpoint(normalizeZero(checkpoint.getFirstCheckpoint()))
                .progressIntervalStep(checkpoint.getProgressIntervalStep())
                .maxCheckpointInterval(checkpoint.getMaxInterval())
                .stagnationEscalationThreshold(checkpoint.getStagnationEscalationThreshold())
                .gentleReminderStartMultiplier(checkpoint.getGentleReminderStartMultiplier())
                .gentleReminderInterval(checkpoint.getGentleReminderInterval())
                .totalBudgetSeconds(timeEnabled ? normalizeZero(time.getTotalBudgetSeconds()) : null)
                .nearDeadlineThresholdSeconds(time.getNearDeadlineThresholdSeconds())
                .build();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "openjiuwen.service.run-budget configuration is invalid: " + exception.getMessage(),
                    exception);
        }
    }

    private static Integer normalizeZero(Integer value) {
        return value != null && value == 0 ? null : value;
    }
}
