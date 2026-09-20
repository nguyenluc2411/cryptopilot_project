/**
 * Shared kernel of the modular monolith: one error model, one base entity, one clock and one
 * rounding policy, so that the twelve business modules never each invent their own.
 *
 * <p>This is an {@link org.springframework.modulith.ApplicationModule.Type#OPEN open} application
 * module. A business module hides everything but the types in its root package; {@code common}
 * instead publishes all of its sub-packages, because every module is meant to reach
 * {@code common.exception}, {@code common.util} and {@code common.web} directly. That is safe here
 * for the reason the boundary check exists: the module holds no business logic and owns no tables,
 * so depending on it cannot couple two modules to each other.
 *
 * <p>Rule: TECHNICAL_DESIGN section 2 (module decomposition, {@code common} row).
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 14 (Shared
 * Kernel: a small, stable subset that several parts of the system share by agreement).
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.cryptopilot.common;

import org.springframework.modulith.ApplicationModule;
