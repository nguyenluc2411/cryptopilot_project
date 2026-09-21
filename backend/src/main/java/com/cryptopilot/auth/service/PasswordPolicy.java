package com.cryptopilot.auth.service;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;

/**
 * BR-02, in one place: a password has 8 to 64 characters and contains at least one upper-case
 * letter, one lower-case letter and one digit.
 *
 * <p>The same rule is also declared on the request record, where bean validation can report it
 * under the field the web client draws it next to. This class is the guarantee and that one is the
 * convenience: a request record is one entry point, while every path that sets a password —
 * registration now, the password reset of BR-04 later, an administrator setting one by hand — goes
 * through a service, and a rule enforced only at the edge is a rule the next entry point forgets.
 *
 * <p>Nothing here logs, echoes or returns the password. The rejection says which rule failed and
 * never what was offered, because an exception message travels into logs that are read by people
 * who should not see a credential even a failed one.
 *
 * <p>Rule: BR-02 (MSG03).
 *
 * <p>Reference: OWASP Application Security Verification Standard v4, section 2.1 (password length
 * and composition are verified server side; the verifier never stores or logs the candidate).
 */
public final class PasswordPolicy {

    /** BR-02: the inclusive bounds on length. */
    static final int MINIMUM_LENGTH = 8;

    static final int MAXIMUM_LENGTH = 64;

    /**
     * BR-02 as one expression, for the request record to declare. Three lookaheads for the three
     * required character classes, then the length bound; {@code .} excludes a line terminator,
     * which no password should contain anyway.
     */
    public static final String PATTERN = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,64}$";

    private PasswordPolicy() {}

    /**
     * Accepts a password that satisfies BR-02 and refuses anything else.
     *
     * @throws BusinessException with {@code PASSWORD_POLICY_VIOLATION} (MSG03). The detail names
     *     the clause that failed and never the value that failed it.
     */
    public static void requireCompliant(String password) {
        if (password == null || password.length() < MINIMUM_LENGTH || password.length() > MAXIMUM_LENGTH) {
            throw violation("a password has between " + MINIMUM_LENGTH + " and " + MAXIMUM_LENGTH + " characters");
        }
        if (!containsAny(password, Character::isUpperCase)) {
            throw violation("a password contains at least one upper-case letter");
        }
        if (!containsAny(password, Character::isLowerCase)) {
            throw violation("a password contains at least one lower-case letter");
        }
        if (!containsAny(password, Character::isDigit)) {
            throw violation("a password contains at least one digit");
        }
    }

    private static boolean containsAny(String password, CharacterTest test) {
        for (int i = 0; i < password.length(); i++) {
            if (test.matches(password.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException violation(String unmetClause) {
        return new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "BR-02 requires that " + unmetClause);
    }

    @FunctionalInterface
    private interface CharacterTest {
        boolean matches(char candidate);
    }
}
