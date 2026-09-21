package com.cryptopilot.user;

/**
 * The role an account holds, as the {@code user} module publishes it to the rest of the application.
 *
 * <h2>Why this exists beside {@code user.entity.Role}</h2>
 *
 * <p>{@link UserSummary} said from the start that it carried no role, and why: {@code Role} lives in
 * {@code user.entity}, a package no other module may reach, so a record that exposed it would have
 * dragged an internal type across the boundary with it. It also said what would happen when another
 * module finally needed the role — it would be published as its own type, by the task that needed
 * it. This is that type, and T-012 is that task: the access token carries a role claim and the sign-in
 * response tells a client whether to open the Trader area or the Admin one (SRS 3.2.3).
 *
 * <p>The cost is one duplicated list of constants, and it is paid deliberately rather than avoided by
 * moving {@code Role} out of {@code user.entity} — the other three enumerations of the module map
 * columns and belong exactly where they are, and moving one of the four because a different module
 * became interested in it would make the package layout a record of who asked rather than of what the
 * class is. A test asserts the two lists are identical, so the duplication cannot drift: adding a role
 * to one and not the other fails the build.
 *
 * <p>Rule: BR-05; TECHNICAL_DESIGN section 2 (a module is reached only through the types in its root
 * package).
 */
public enum UserRole {

    /** A self-registered user. Every account starts here and BR-05 offers no other way in. */
    TRADER,

    /** An administrator. Granted only by another administrator (BR-05). */
    ADMIN
}
