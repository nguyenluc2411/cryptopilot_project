package com.cryptopilot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The same layer rules as {@link LayerRulesTest}, asserted against the import statements instead of
 * against the bytecode.
 *
 * <h2>Why both</h2>
 *
 * <p>ArchUnit reads compiled classes, and a dependency on a constant does not survive compilation.
 * {@code public static final String PATTERN = "..."} is a constant variable, so javac copies its
 * value into every class that reads it and leaves no reference behind; an annotation argument is
 * required to be constant and is therefore always copied. A class can import from a layer it is
 * forbidden to touch, use only a constant from it, and pass {@link LayerRulesTest} — which is
 * exactly what {@code RegisterRequest} did while it read the password expression out of the service
 * layer. The rule was not wrong, and nothing was failing; the evidence had simply been optimised
 * away before the rule could see it.
 *
 * <p>The cost of the same rule stated twice is that the two can drift, so this one is written from
 * the same table and covers only the pairs inside {@code com.cryptopilot}. Framework dependencies
 * (a calculator reaching Spring or JPA) stay in {@link LayerRulesTest} alone, where the bytecode is
 * the honest source: those arrive through types, never through a constant.
 *
 * <p>An import is a coarse signal — it is neither necessary (a fully qualified name needs none) nor
 * precise (an unused one still counts). Both directions are acceptable here: unused imports fail
 * the build already, and a fully qualified reference to a type leaves the bytecode evidence that
 * the other test reads.
 *
 * <p>Rule: SRS 4.2.5 (maintainability through separated layers and components).
 *
 * <p>Reference: Gosling, J., Joy, B., Steele, G., Bracha, G. &amp; Buckley, A. (2021). <i>The Java
 * Language Specification, Java SE 17 Edition</i>, section 4.12.4 (a constant variable's value is
 * inlined at every use) and section 9.7.1 (an annotation element's value must be a constant
 * expression).
 * <p>Reference: Humble, J. &amp; Farley, D. (2010). <i>Continuous Delivery</i>. Addison-Wesley,
 * ch. 3 (a rule is only real once it fails the build).
 */
class LayerSourceRulesTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    private static final String ROOT_PACKAGE = "com.cryptopilot.";

    /** Each layer, and the layers its classes may not import from. Mirrors {@link LayerRulesTest}. */
    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            "controller", List.of("repository", "entity"),
            "service", List.of("controller"),
            "repository", List.of("service", "controller"),
            "entity", List.of("service", "repository", "controller"),
            "dto", List.of("service", "repository"),
            "calculator", List.of("repository", "service", "controller", "client", "job"),
            "client", List.of("repository", "controller"),
            "job", List.of("repository"));

    @Test
    void everyLayerRule_holdsOverTheImportsAndNotOnlyOverTheBytecode() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            sources.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
        }

        assertThat(violations)
                .as("a class may not import from a layer its own layer is forbidden to depend on, "
                        + "whether or not the compiler leaves a trace of it")
                .isEmpty();
    }

    private static void collectViolations(Path source, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(source);
        } catch (IOException failed) {
            throw new UncheckedIOException("cannot read " + source, failed);
        }

        String ownLayer = layerOf(packageOf(lines));
        List<String> forbidden = FORBIDDEN.getOrDefault(ownLayer, List.of());
        if (forbidden.isEmpty()) {
            return;
        }

        for (String line : lines) {
            String imported = importedPackageOf(line);
            if (imported == null) {
                continue;
            }
            String importedLayer = layerOf(imported);
            if (forbidden.contains(importedLayer)) {
                violations.add("%s (layer %s) imports from layer %s: %s"
                        .formatted(source, ownLayer, importedLayer, line.trim()));
            }
        }
    }

    private static String packageOf(List<String> lines) {
        return lines.stream()
                .filter(line -> line.startsWith("package "))
                .findFirst()
                .map(line ->
                        line.substring("package ".length()).replace(";", "").trim())
                .orElse("");
    }

    /**
     * The package an import names, or {@code null} when the line is not an import of our own code.
     * The trailing segment is the type, and a nested type adds another, but the layer is always the
     * fourth segment, so dropping the last one is enough to stop a type name being read as a layer.
     */
    private static String importedPackageOf(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ") || !trimmed.endsWith(";")) {
            return null;
        }
        String reference = trimmed.substring("import ".length(), trimmed.length() - 1)
                .replace("static ", "")
                .trim();
        if (!reference.startsWith(ROOT_PACKAGE)) {
            return null;
        }
        int lastDot = reference.lastIndexOf('.');
        return lastDot < 0 ? reference : reference.substring(0, lastDot);
    }

    /**
     * The layer a package sits in: {@code com.cryptopilot.<module>.<layer>}. Returns an empty string
     * for a module's root package, which is its public API and belongs to no layer.
     */
    private static String layerOf(String packageName) {
        if (!packageName.startsWith(ROOT_PACKAGE)) {
            return "";
        }
        String[] segments = packageName.split("\\.");
        return segments.length >= 4 ? segments[3] : "";
    }
}
