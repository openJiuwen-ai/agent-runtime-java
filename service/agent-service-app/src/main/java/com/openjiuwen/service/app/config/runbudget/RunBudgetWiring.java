/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.runbudget;

import com.openjiuwen.service.app.config.llm.LlmProperties;

/**
 * Inputs for FEAT-057 run-budget wiring at the agent factory boundary.
 *
 * <p>The raw {@link LlmProperties} is required because the resolved config normalizes
 * {@code max-iterations} to a primitive, which erases the difference between an explicit
 * value and the default; the elastic turn budget must yield to an explicit legacy limit.</p>
 *
 * @param llmProperties raw LLM properties (used for the explicit-max-iterations check)
 * @param budgetProperties raw run-budget properties
 * @since 2026-10-08
 */
public record RunBudgetWiring(LlmProperties llmProperties, RunBudgetProperties budgetProperties) {
}
