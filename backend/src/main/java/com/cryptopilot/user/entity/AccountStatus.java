package com.cryptopilot.user.entity;

/**
 * The lifecycle state of an account.
 *
 * <p>An account is never deleted — the administration screens offer Lock, Unlock and Ban, and the
 * foreign keys of the schema refuse a delete while any trading history, order or post exists — so
 * this enumeration is the whole of the lifecycle. {@code BANNED} is terminal for the same reason
 * the screens list no Unban action.
 *
 * <p>The constants are the values of the {@code ck_user_account_status} check constraint, spelled
 * the same way.
 *
 * <p>Rule: BR-05, BR-06.
 */
public enum AccountStatus {

    /** Usable. The only state from which a sign-in can succeed (BR-06). */
    ACTIVE,

    /** Temporarily suspended by an administrator. Reversible with {@code unlock}. */
    LOCKED,

    /** Permanently suspended. No action returns an account from here. */
    BANNED
}
