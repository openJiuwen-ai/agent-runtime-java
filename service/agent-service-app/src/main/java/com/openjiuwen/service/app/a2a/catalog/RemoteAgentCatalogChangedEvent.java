/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

/**
 * Event published after the remote A2A agent catalog changes.
 *
 * @param snapshot complete catalog snapshot produced by the registry update
 * @since 0.1.1
 */
public record RemoteAgentCatalogChangedEvent(RemoteAgentCatalogSnapshot snapshot) {
}
