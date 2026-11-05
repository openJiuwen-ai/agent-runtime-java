/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.store.kv.InMemoryKVStore;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.checkpointer.step.KvStepJournal;
import com.openjiuwen.core.session.checkpointer.step.StepCheckpointRecoveryService;
import com.openjiuwen.core.session.checkpointer.step.StepRecord;
import com.openjiuwen.core.session.checkpointer.step.StepStatus;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests the dead-step sweeper's companion journal purge: every sweep pass
 * removes step-journal records older than the configured retention, so audit
 * cleanup rides the same cadence as dead-step recovery.
 */
class DeadStepSweeperJournalPurgeTest {
    @Test
    void sweepPurgesJournalRecordsOlderThanRetention() {
        InMemoryKVStore kvStore = new InMemoryKVStore();
        KvStepJournal journal = new KvStepJournal(kvStore);
        journal.append(new StepRecord("session-old", "agent", 1L, "TOOL_CALL", "old",
                "", StepStatus.COMPLETED, 100L));
        journal.append(new StepRecord("session-new", "agent", 2L, "TOOL_CALL", "new",
                "", StepStatus.COMPLETED, System.currentTimeMillis()));

        DeadStepSweeper sweeper = new DeadStepSweeper(new InMemoryCheckpointer(), null, 60L,
                journal, 60_000L);
        List<StepCheckpointRecoveryService.RecoveryAction> actions = sweeper.sweepOnce();

        assertThat(actions).isEmpty();
        assertThat(journal.replay("session-old")).isEmpty();
        assertThat(journal.replay("session-new")).hasSize(1);
        sweeper.stop();
    }

    @Test
    void sweepSkipsPurgeWhenJournalOrRetentionMissing() {
        InMemoryKVStore kvStore = new InMemoryKVStore();
        KvStepJournal journal = new KvStepJournal(kvStore);
        journal.append(new StepRecord("session-keep", "agent", 1L, "TOOL_CALL", "old",
                "", StepStatus.COMPLETED, 100L));

        // No journal wired: the sweep must not touch the store.
        DeadStepSweeper withoutJournal = new DeadStepSweeper(new InMemoryCheckpointer(), null, 60L);
        withoutJournal.sweepOnce();
        assertThat(journal.replay("session-keep")).hasSize(1);
        withoutJournal.stop();

        // Journal wired but retention disabled: the records survive.
        DeadStepSweeper retentionDisabled = new DeadStepSweeper(new InMemoryCheckpointer(), null, 60L,
                journal, 0L);
        retentionDisabled.sweepOnce();
        assertThat(journal.replay("session-keep")).hasSize(1);
        retentionDisabled.stop();
    }

    @Test
    void sweepSurvivesJournalPurgeFailure() {
        KvStepJournal brokenJournal = new KvStepJournal(null) {
            @Override
            public int purgeBefore(long beforeEpochMillis) {
                throw new IllegalStateException("simulated purge outage");
            }
        };

        DeadStepSweeper sweeper = new DeadStepSweeper(new InMemoryCheckpointer(), null, 60L,
                brokenJournal, 60_000L);
        List<StepCheckpointRecoveryService.RecoveryAction> actions = sweeper.sweepOnce();

        assertThat(actions).isEmpty();
        sweeper.stop();
    }

    @Test
    void legacyConstructorKeepsSweepBehaviorWithoutJournal() {
        DeadStepSweeper sweeper = new DeadStepSweeper(new InMemoryCheckpointer(), null, 60L);

        List<StepCheckpointRecoveryService.RecoveryAction> actions = sweeper.sweepOnce();

        assertThat(actions).isEmpty();
        sweeper.stop();
    }
}
