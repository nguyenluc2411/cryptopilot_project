package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.FuturesWarningType;
import java.util.Objects;

/**
 * One risk warning of a paper Futures order (TR-26).
 *
 * @param type the kind of warning
 * @param severity the severity its type gives it
 * @param message the warning, with the values that raised it
 */
public record FuturesWarning(FuturesWarningType type, FuturesWarningType.Severity severity, String message) {

    public FuturesWarning {
        Objects.requireNonNull(type, "type");
        if (severity != type.severity()) {
            throw new IllegalArgumentException(type + " is " + type.severity() + ", not " + severity);
        }
        Objects.requireNonNull(message, "message");
    }

    /** A warning of {@code type}, at the severity the type gives it. */
    public FuturesWarning(FuturesWarningType type, String message) {
        this(Objects.requireNonNull(type, "type"), type.severity(), message);
    }
}
