package com.cryptopilot.user.entity;

/**
 * The role an account holds. Every account has exactly one, and there are exactly two.
 *
 * <p>The constants are the values of the {@code ck_user_account_role} check constraint, spelled the
 * same way, because the column stores the name of the constant and the database refuses anything
 * that is not in its list. Adding a constant here without a migration makes every insert of that
 * value fail, which is the intended direction: the schema decides what a role may be.
 *
 * <p>Rule: BR-05.
 */
public enum Role {

    /** The default role of every account that registers itself (BR-05). */
    TRADER,

    /** Administers accounts, moderation, configuration and reports. Granted only by an Admin. */
    ADMIN
}
