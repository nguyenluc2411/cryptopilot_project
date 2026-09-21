package com.cryptopilot.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.user.entity.Role;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The published role and the mapped one say the same thing.
 *
 * <p>{@link UserRole} is a deliberate duplicate: {@code user.entity.Role} maps a column and may not
 * cross the module boundary, so the role another module reads is published as its own type. A
 * duplicate is only safe while it cannot drift, and this is what stops it — adding a role to one
 * enumeration and not the other fails the build rather than becoming an {@code IllegalArgumentException}
 * the first time an account holding the new role signs in.
 *
 * <p>Rule: BR-05; TECHNICAL_DESIGN section 2.
 */
class UserRoleTest {

    @Test
    void BR05_thePublishedRoles_areExactlyTheMappedOnes() {
        assertThat(Arrays.stream(UserRole.values()).map(Enum::name))
                .as("one list of roles, published under two names")
                .containsExactlyElementsOf(
                        Arrays.stream(Role.values()).map(Enum::name).toList());
    }

    @Test
    void BR05_everyMappedRole_convertsToItsPublishedName() {
        for (Role mapped : Role.values()) {
            assertThat(UserRole.valueOf(mapped.name())).hasToString(mapped.name());
        }
    }
}
