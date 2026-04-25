package com.openjiuwen.a2a_service.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Simulate API routes — 测试用路由。
 */
@RestController
public class Simulate {

    private static final Logger logger = LoggerFactory.getLogger(Simulate.class);

    @GetMapping("/gh")
    public ResponseEntity<String> root() {
        try {
            ClassPathResource resource = new ClassPathResource("static/index.html");
            try (InputStream is = resource.getInputStream()) {
                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(html);
            }
        } catch (IOException e) {
            logger.warn("Simulate HTML not found", e);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>Simulate HTML not found.</h1>");
        }
    }
}
