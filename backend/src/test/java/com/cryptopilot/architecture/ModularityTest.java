package com.cryptopilot.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.cryptopilot.CryptoPilotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies the module boundaries of the modular monolith: every direct sub-package of
 * {@code com.cryptopilot} is an application module, a module may be reached only through the
 * classes in its root package and its {@code event} package, and no cycles exist between modules.
 *
 * <p>Because a repository and its entities are internal to their module, this test is also what
 * stops one module from writing another module's tables: reaching the other module's
 * {@code repository} or {@code entity} package is an access violation.
 *
 * <p>Rule: SRS 4.2.5 (maintainability through separated layers and components).
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 1 and 13
 * (module boundaries in a modular monolith, and enforcing them so they cannot erode).
 * <p>Reference: Humble, J. &amp; Farley, D. (2010). <i>Continuous Delivery</i>. Addison-Wesley, ch. 3
 * (a rule is only real once it fails the build).
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(CryptoPilotApplication.class);

    @Test
    void modules_whenVerified_respectBoundariesAndHaveNoCycles() {
        assertThatCode(modules::verify).doesNotThrowAnyException();
    }

    /**
     * Writes the module canvas and component diagrams to {@code target/spring-modulith-docs}. This
     * is generated documentation, not an assertion; it fails only if the module structure cannot be
     * rendered, which means the structure itself is broken.
     */
    @Test
    void modules_whenDocumented_renderWithoutError() {
        assertThatCode(() -> new Documenter(modules).writeDocumentation()).doesNotThrowAnyException();
    }
}
