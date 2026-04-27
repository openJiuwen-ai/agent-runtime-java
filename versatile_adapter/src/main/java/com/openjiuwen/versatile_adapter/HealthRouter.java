package com.openjiuwen.versatile_adapter;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthRouter {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "healthy", "service", "VersatileAdapter");
    }
}
