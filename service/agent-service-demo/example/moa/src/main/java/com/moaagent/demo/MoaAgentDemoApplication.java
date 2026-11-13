/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single entry point for the L1, L2 and mock profiles.
 *
 * @since 0.1.3
 */
@SpringBootApplication
public class MoaAgentDemoApplication {
    /**
     * Starts the demo role selected by the Spring profile.
     *
     * @param args Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(MoaAgentDemoApplication.class, args);
    }
}
