/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.runbudget;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw run-budget configuration bound from {@code openjiuwen.service.run-budget}.
 *
 * <p>Covers both FEAT-057 dimensions: the turn dimension (guarantee, checkpoint cadence,
 * hard limit) and the time dimension (total wall-clock budget, near-deadline threshold).
 * A zero or absent optional value means "not declared"; negative values are rejected by the
 * consuming factory wiring.</p>
 *
 * @since 2026-10-08
 */
@ConfigurationProperties(prefix = "openjiuwen.service.run-budget")
public class RunBudgetProperties {
    private Turn turn = new Turn();
    private Time time = new Time();

    /**
     * Returns the turn-dimension configuration.
     *
     * @return turn configuration, never null
     */
    public Turn getTurn() {
        return turn;
    }

    /**
     * Returns the time-dimension configuration.
     *
     * @return time configuration, never null
     */
    public Time getTime() {
        return time;
    }

    /**
     * Sets the turn-dimension configuration.
     *
     * @param turn turn configuration
     */
    public void setTurn(Turn turn) {
        this.turn = turn == null ? new Turn() : turn;
    }

    /**
     * Sets the time-dimension configuration.
     *
     * @param time time configuration
     */
    public void setTime(Time time) {
        this.time = time == null ? new Time() : time;
    }

    /**
     * Turn-dimension (round budget) configuration.
     *
     * @since 2026-10-08
     */
    public static class Turn {
        private boolean enabled;
        private Integer suggestedRounds;
        private int defaultGuaranteedRounds = 30;
        private Integer hardLimit;
        private Checkpoint checkpoint = new Checkpoint();

        /**
         * Returns whether the turn dimension is enabled.
         *
         * @return enabled flag
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Returns the suggested rounds; null or zero means undeclared.
         *
         * @return suggested rounds or null
         */
        public Integer getSuggestedRounds() {
            return suggestedRounds;
        }

        /**
         * Returns the default guaranteed rounds used without a suggestion.
         *
         * @return default guaranteed rounds
         */
        public int getDefaultGuaranteedRounds() {
            return defaultGuaranteedRounds;
        }

        /**
         * Returns the explicit hard limit; null or zero derives {@code max(guarantee * 2, 200)}.
         *
         * @return hard limit or null
         */
        public Integer getHardLimit() {
            return hardLimit;
        }

        /**
         * Returns the checkpoint cadence configuration.
         *
         * @return checkpoint configuration, never null
         */
        public Checkpoint getCheckpoint() {
            return checkpoint;
        }

        /**
         * Sets whether the turn dimension is enabled.
         *
         * @param enabled enabled flag
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Sets the suggested rounds.
         *
         * @param suggestedRounds suggested rounds
         */
        public void setSuggestedRounds(Integer suggestedRounds) {
            this.suggestedRounds = suggestedRounds;
        }

        /**
         * Sets the default guaranteed rounds.
         *
         * @param defaultGuaranteedRounds default guaranteed rounds
         */
        public void setDefaultGuaranteedRounds(int defaultGuaranteedRounds) {
            this.defaultGuaranteedRounds = defaultGuaranteedRounds;
        }

        /**
         * Sets the explicit hard limit.
         *
         * @param hardLimit hard limit
         */
        public void setHardLimit(Integer hardLimit) {
            this.hardLimit = hardLimit;
        }

        /**
         * Sets the checkpoint cadence configuration.
         *
         * @param checkpoint checkpoint configuration
         */
        public void setCheckpoint(Checkpoint checkpoint) {
            this.checkpoint = checkpoint == null ? new Checkpoint() : checkpoint;
        }
    }

    /**
     * Checkpoint cadence configuration of the turn dimension.
     *
     * @since 2026-10-08
     */
    public static class Checkpoint {
        private Integer firstCheckpoint;
        private int progressIntervalStep = 10;
        private int maxInterval = 50;
        private int stagnationEscalationThreshold = 3;
        private int gentleReminderStartMultiplier = 2;
        private int gentleReminderInterval = 15;

        /**
         * Returns the explicit first checkpoint; null or zero derives {@code max(10, guarantee)}.
         *
         * @return first checkpoint or null
         */
        public Integer getFirstCheckpoint() {
            return firstCheckpoint;
        }

        /**
         * Returns the per-progress interval step.
         *
         * @return interval step in rounds
         */
        public int getProgressIntervalStep() {
            return progressIntervalStep;
        }

        /**
         * Returns the interval ceiling.
         *
         * @return maximum interval in rounds
         */
        public int getMaxInterval() {
            return maxInterval;
        }

        /**
         * Returns the consecutive-stall escalation threshold.
         *
         * @return escalation threshold in checkpoints
         */
        public int getStagnationEscalationThreshold() {
            return stagnationEscalationThreshold;
        }

        /**
         * Returns the guarantee multiplier at which gentle reminders start.
         *
         * @return start multiplier
         */
        public int getGentleReminderStartMultiplier() {
            return gentleReminderStartMultiplier;
        }

        /**
         * Returns the gentle reminder interval.
         *
         * @return reminder interval in rounds
         */
        public int getGentleReminderInterval() {
            return gentleReminderInterval;
        }

        /**
         * Sets the explicit first checkpoint.
         *
         * @param firstCheckpoint first checkpoint round
         */
        public void setFirstCheckpoint(Integer firstCheckpoint) {
            this.firstCheckpoint = firstCheckpoint;
        }

        /**
         * Sets the per-progress interval step.
         *
         * @param progressIntervalStep interval step in rounds
         */
        public void setProgressIntervalStep(int progressIntervalStep) {
            this.progressIntervalStep = progressIntervalStep;
        }

        /**
         * Sets the interval ceiling.
         *
         * @param maxInterval maximum interval in rounds
         */
        public void setMaxInterval(int maxInterval) {
            this.maxInterval = maxInterval;
        }

        /**
         * Sets the consecutive-stall escalation threshold.
         *
         * @param stagnationEscalationThreshold escalation threshold in checkpoints
         */
        public void setStagnationEscalationThreshold(int stagnationEscalationThreshold) {
            this.stagnationEscalationThreshold = stagnationEscalationThreshold;
        }

        /**
         * Sets the guarantee multiplier at which gentle reminders start.
         *
         * @param gentleReminderStartMultiplier start multiplier
         */
        public void setGentleReminderStartMultiplier(int gentleReminderStartMultiplier) {
            this.gentleReminderStartMultiplier = gentleReminderStartMultiplier;
        }

        /**
         * Sets the gentle reminder interval.
         *
         * @param gentleReminderInterval reminder interval in rounds
         */
        public void setGentleReminderInterval(int gentleReminderInterval) {
            this.gentleReminderInterval = gentleReminderInterval;
        }
    }

    /**
     * Time-dimension (wall-clock budget) configuration.
     *
     * @since 2026-10-08
     */
    public static class Time {
        private Integer totalBudgetSeconds;
        private int nearDeadlineThresholdSeconds = 60;

        /**
         * Returns the total wall-clock budget in seconds; null or zero means undeclared.
         *
         * @return total budget seconds or null
         */
        public Integer getTotalBudgetSeconds() {
            return totalBudgetSeconds;
        }

        /**
         * Returns the near-deadline threshold in seconds.
         *
         * @return near-deadline threshold seconds
         */
        public int getNearDeadlineThresholdSeconds() {
            return nearDeadlineThresholdSeconds;
        }

        /**
         * Sets the total wall-clock budget in seconds.
         *
         * @param totalBudgetSeconds total budget seconds
         */
        public void setTotalBudgetSeconds(Integer totalBudgetSeconds) {
            this.totalBudgetSeconds = totalBudgetSeconds;
        }

        /**
         * Sets the near-deadline threshold in seconds.
         *
         * @param nearDeadlineThresholdSeconds near-deadline threshold seconds
         */
        public void setNearDeadlineThresholdSeconds(int nearDeadlineThresholdSeconds) {
            this.nearDeadlineThresholdSeconds = nearDeadlineThresholdSeconds;
        }
    }
}
