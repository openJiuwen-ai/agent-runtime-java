/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Architecture guard codifying the FEAT-062 L2 zero-dependency constraint:
 * the runtime main sources never reference the unified registry API package
 * ({@code com.openjiuwen.agent.registry}) or the ext direct-registry module,
 * and no runtime POM ever declares the unified API client or ext artifacts.
 * The legal dependency direction is ext {@literal ->} runtime; this guard
 * keeps the reverse direction impossible at compile time.
 *
 * @since 0.1.4
 */
class RuntimeRegistryDependencyArchTest {

    private static final String FORBIDDEN_UNIFIED_REGISTRY_PACKAGE = "com.openjiuwen.agent.registry";

    private static final String FORBIDDEN_EXT_PACKAGE = "com.openjiuwen.service.app.directregistry";

    private static final List<String> FORBIDDEN_POM_FRAGMENTS = List.of(FORBIDDEN_UNIFIED_REGISTRY_PACKAGE,
            "agent-registry", "direct-registry");

    @Test
    void runtimeMainSourcesNeverReferenceTheUnifiedRegistryApiOrTheExtensionModule() throws IOException {
        List<Path> offenders = new ArrayList<>();
        for (Path sourceRoot : mainSourceRoots()) {
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                sources.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> referencesForbiddenPackages(path, sourceRoot))
                        .forEach(offenders::add);
            }
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    void runtimePomsNeverDeclareTheUnifiedRegistryClientOrExtensionArtifacts() throws IOException {
        List<String> offendingPoms = new ArrayList<>();
        for (Path pom : pomFiles()) {
            String content = Files.readString(pom);
            for (String fragment : FORBIDDEN_POM_FRAGMENTS) {
                if (content.contains(fragment)) {
                    offendingPoms.add(pom + " (" + fragment + ")");
                }
            }
        }

        assertThat(offendingPoms).isEmpty();
    }

    private boolean referencesForbiddenPackages(Path source, Path sourceRoot) {
        try {
            String content = Files.readString(source);
            return content.contains(FORBIDDEN_UNIFIED_REGISTRY_PACKAGE)
                    || content.contains(FORBIDDEN_EXT_PACKAGE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read runtime source " + sourceRoot.relativize(source), e);
        }
    }

    private static List<Path> mainSourceRoots() {
        List<Path> roots = new ArrayList<>();
        for (Path moduleRoot : moduleRoots()) {
            Path mainJava = moduleRoot.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(mainJava)) {
                roots.add(mainJava);
            }
        }
        return roots;
    }

    private static List<Path> pomFiles() {
        List<Path> poms = new ArrayList<>();
        for (Path moduleRoot : moduleRoots()) {
            Path pom = moduleRoot.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                poms.add(pom);
            }
        }
        return poms;
    }

    /**
     * Walks up from the test working directory (the module basedir) to the
     * repository root — the last directory whose parent still holds a POM —
     * then collects every directory of the repository holding a POM.
     */
    private static List<Path> moduleRoots() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory.getParent() != null && Files.isRegularFile(directory.getParent().resolve("pom.xml"))) {
            directory = directory.getParent();
        }
        List<Path> moduleRoots = new ArrayList<>();
        try (Stream<Path> directories = Files.walk(directory)) {
            directories.filter(member -> Files.isRegularFile(member.resolve("pom.xml")))
                    .filter(member -> !member.toString().contains("target"))
                    .forEach(moduleRoots::add);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk the runtime repository root " + directory, e);
        }
        return moduleRoots;
    }
}
