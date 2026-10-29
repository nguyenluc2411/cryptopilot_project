package com.cryptopilot.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The catalogue of error codes the API can return. Each constant ties three things together that
 * must never drift apart: the stable machine-readable code a client branches on, the HTTP status
 * the response carries, and the SRS message code that tells the client which text of SRS section
 * 5.3 to display.
 *
 * <p>The name of the constant is the stable code. It is part of the published API, so a constant is
 * renamed only with a breaking API change; adding a constant is not breaking.
 *
 * <p>Only the codes the shared kernel itself raises live here so far. Each business module adds its
 * own constants as its use cases are built, and {@code ErrorCodeTest} keeps the invariant that every
 * constant names exactly one existing {@code MSGxx} id.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1; SRS section 5.3 (application messages MSG01…MSG49 in v1.1).
 *
 * <p>Reference: Nottingham, M., Wilde, E. &amp; Dalal, S. (2023). RFC 9457, <i>Problem Details for
 * HTTP APIs</i>, section 3 (a problem type is a stable identifier, separate from the status code).
 */
public enum ErrorCode {

    /**
     * The request failed validation. The offending fields are reported one by one in the
     * {@code errors} property of the response; MSG01 is the generic "the request is not valid"
     * text, while the per-field message carries the specific rule (MSG02, MSG03, MSG15, MSG16 …).
     */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "MSG01"),

    /** The addressed resource does not exist, or is not visible to the caller. */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "MSG41"),

    /**
     * Nothing in the catalogue matched: an unhandled exception reached the boundary. The response
     * carries a reference code and no technical detail (SRS 4.2.4).
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "MSG43"),

    /**
     * The address or the password was wrong (SRS 3.2.3). One code for both, because MSG08 is one
     * sentence for both: saying which of the two was wrong turns the endpoint into a way of finding
     * out which addresses hold accounts.
     *
     * <p>401 rather than 400: the request was well formed and the credentials it carried were
     * refused, which is exactly what the status means.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MSG08"),

    /**
     * Five consecutive failures have locked the account for fifteen minutes (BR-03). MSG09 carries
     * the minutes still to wait as its one message argument.
     *
     * <p>429 rather than 401 or 403: nothing is wrong with the credentials this request carried - the
     * account is refusing attempts for a while because too many arrived, which is the one situation
     * 429 describes. It also tells an automated client the right thing, where 401 would invite it to
     * try again immediately.
     */
    LOGIN_TEMPORARILY_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "MSG09"),

    /**
     * The account is LOCKED or BANNED, so it cannot sign in (BR-06). 403 rather than 401: the
     * credentials were not the problem, and MSG10 tells the holder which of the two states applies -
     * as its one message argument, because the sentence names the state.
     */
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "MSG10"),

    /**
     * The refresh token presented was unknown, already used, expired, or belongs to a family that has
     * been revoked (SRS 3.2.3, TECHNICAL_DESIGN 7.15). One code and one message for all four, and
     * deliberately: from the holder's side the session is over either way, and telling a caller which
     * of the four applies tells whoever is holding a copied token how far they got.
     *
     * <p>MSG44 is the text SRS 3.2.3 assigns an expired session.
     */
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "MSG44"),

    /**
     * The account has not verified its address, so it cannot sign in yet (BR-01). MSG11 carries the
     * offer to send the verification mail again.
     */
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "MSG11"),

    /**
     * A verification was applied to an account that is already verified. From the holder's side
     * this is a link that has been used once already, which is what MSG07 says.
     */
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "MSG07"),

    /**
     * An administrator's status change does not exist in the account lifecycle — unlocking an
     * account that is not locked, or any action on a banned one (BR-05, BR-06).
     *
     * <p>SRS section 5.3 has no error text for this. MSG39 is the only message whose context is an
     * administrator changing an account status, and it is written as a confirmation prompt rather
     * than a refusal, so it is named here as the nearest existing message while the alignment item
     * that asks for a proper one is open.
     */
    ACCOUNT_STATUS_TRANSITION_INVALID(HttpStatus.CONFLICT, "MSG39"),

    /**
     * A single-use token was presented after it had been used or after it expired (BR-01, BR-04).
     * MSG07 is the text for both, deliberately: telling the two apart would say whether the link
     * ever existed.
     */
    TOKEN_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "MSG07"),

    /**
     * The address is already registered (SRS UC-01). A conflict rather than a validation error,
     * because nothing about the request is malformed: it asks for something that already exists.
     *
     * <p>The SRS chooses to say so. MSG04 tells a visitor that the address is taken, which does
     * reveal that an account holds it; the password reset flow of BR-04 is the one that must not
     * (MSG12), and the two are deliberately different because a registration form that refused to
     * say would instead fail at the second step and confuse everyone who mistyped.
     */
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "MSG04"),

    /**
     * The password does not satisfy BR-02: 8 to 64 characters, with at least one upper-case letter,
     * one lower-case letter and one digit.
     */
    PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "MSG03"),

    /**
     * A write lost a race with another one: a unique constraint refused the row because an equal
     * one was committed first. 409 rather than 500, because nothing is broken — the same request
     * sent again will be answered properly, by the rule that owns the constraint.
     *
     * <p>This is the safety net, not the answer a user should normally see. A use case that knows
     * which constraint it can collide with catches the violation and raises its own code with the
     * message SRS 5.3 assigns it; registration will raise MSG04 for a duplicate address. What is
     * left for this code is the collision nobody anticipated, and SRS 5.3 has no text for that, so
     * it borrows the reference-code message of MSG43 while the alignment item asking for one is
     * open.
     */
    DATA_CONFLICT(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The current password offered on the Security tab is wrong (SRS 3.2.5, UC-07). MSG08 is the
     * message SRS 3.2.5 assigns it.
     *
     * <p>A code of its own rather than {@link #INVALID_CREDENTIALS}, because the status must differ:
     * that one is 401, and a 401 on a request that carried a valid access token tells a client its
     * session is over, which would send somebody who mistyped their password back to the sign-in
     * screen. This caller is authenticated; what was wrong was a field of the form, so 400.
     */
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "MSG08"),

    /**
     * The request carried no usable credential, so the caller is not authenticated (SRS 4.2.4).
     *
     * <p>Raised by the filter chain rather than by a use case: a missing, malformed, expired or
     * wrongly signed bearer token all end here, and deliberately as one code. Which of the four
     * applies is of interest only to whoever is holding the token, and the client's action is the
     * same in every case - obtain a new session, or send the holder to the sign-in screen.
     *
     * <p>MSG44 is the text SRS 3.2.3 assigns an ended session, and it is the right thing to show:
     * from the holder's side that is exactly what has happened. It is shared with
     * {@link #SESSION_EXPIRED}, which is the same fact discovered one layer lower - a refresh token
     * that no longer renews anything. The codes stay separate because they are raised by different
     * things and a client may want to tell them apart; the text does not, because the person reading
     * it would not.
     */
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "MSG44"),

    /**
     * The caller is authenticated and the role they hold does not reach this endpoint (SRS 3.1.3,
     * SRS 4.2.4).
     *
     * <p>403 rather than 401, and the distinction is the whole point: 401 says the credential was
     * missing or bad and inviting the caller to present another one is sensible, while 403 says the
     * credential was fine and presenting it again will not help.
     *
     * <p>The response names no role and no requirement. A Trader who probes an administration
     * endpoint learns that it exists and refuses them, which is unavoidable, and nothing further -
     * telling them which role would open it is a small piece of the system's shape given away for
     * no benefit to anyone entitled to be there.
     *
     * <p>SRS section 5.3 has no message for a refused authorization. MSG43's reference-code text is
     * borrowed while the alignment item asking for a proper one is open, the same way
     * {@link #DATA_CONFLICT} borrows it, and the detail sentence carries the real meaning so that a
     * client which shows the detail is not made to lie. A correctly built client should not reach
     * this code at all: the screens a role cannot open are not offered to it, so a 403 here means
     * either a direct call to the API or a defect.
     */
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "MSG43"),

    /**
     * The caller's plan does not include the feature (BR-62, CR-08). MSG29 names the lowest plan that does, as its
     * one message argument.
     *
     * <p>403, like {@link #ACCESS_DENIED}: the credential is fine and presenting it again will not help; upgrading the
     * plan will (D-61).
     */
    PLAN_FEATURE_NOT_INCLUDED(HttpStatus.FORBIDDEN, "MSG29"),

    /**
     * The caller already holds as many items as the plan allows (BR-15, BR-17, BR-62). MSG27 takes the maximum, the
     * items and the plan, in that order.
     *
     * <p>409: the request is valid and would succeed with fewer existing items, so it conflicts with the current state
     * rather than with the caller's rights (D-61).
     */
    PLAN_LIMIT_REACHED(HttpStatus.CONFLICT, "MSG27"),

    /**
     * Today's AI Assistant questions are used up (BR-50). MSG30 takes the daily quota; the quota resets at 00:00
     * UTC+7.
     *
     * <p>429, like {@link #LOGIN_TEMPORARILY_LOCKED}: the same request succeeds once the day turns (D-61).
     */
    AI_DAILY_QUOTA_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS, "MSG30"),

    /**
     * Too many requests from one client address or one account in the current window (ADR-014). MSG50 takes the
     * seconds until the window ends, the same number as the {@code Retry-After} header.
     *
     * <p>429, like {@link #LOGIN_TEMPORARILY_LOCKED}: the same request succeeds once the window turns. MSG50 is new;
     * SRS 5.3 had no message for a request limit (A-42).
     */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "MSG50"),

    /**
     * The Trader switches to the AGGRESSIVE risk profile without confirming MSG48's warning (BR-66, SRS 3.2.5). The
     * client shows MSG48 and sends the choice again, confirmed.
     *
     * <p>400, the status every business validation error of this API answers with (MSG01, MSG03, MSG07, MSG08); 422
     * would be the only one of its kind (D-65).
     */
    RISK_PROFILE_CONFIRMATION_REQUIRED(HttpStatus.BAD_REQUEST, "MSG48"),

    /**
     * The plan holds a BLOCKING warning, so it cannot be activated (BR-25, BR-26, BR-28, BR-32). It can still be
     * saved as a draft.
     *
     * <p>409, as {@link #PLAN_LIMIT_REACHED}: the request is valid and the plan's current warnings are what refuse it
     * (D-66).
     */
    TRADING_BLOCKING_WARNING(HttpStatus.CONFLICT, "MSG18"),

    /**
     * The expected fill of a MARKET plan has moved from the plan by at least {@code ENTRY_DEVIATION_CONFIRM_R} and at
     * most the plan's limit (BR-69). The body carries the deviation and the recalculated quantity and risk/reward, so
     * the client shows MSG51 and repeats the request with {@code confirmDeviation=true}.
     *
     * <p>400, as {@link #RISK_PROFILE_CONFIRMATION_REQUIRED}: a confirmation the request did not carry (D-65).
     */
    ENTRY_DEVIATION_CONFIRMATION_REQUIRED(HttpStatus.BAD_REQUEST, "MSG51"),

    /**
     * The expected fill of a MARKET plan has moved from the plan by more than its limit, or is at or past its take
     * profit (BR-69). No confirmation lifts it; the plan stays a draft.
     *
     * <p>409, as {@link #TRADING_BLOCKING_WARNING}: the request is valid and the current price is what refuses it.
     */
    ENTRY_DEVIATION_REFUSED(HttpStatus.CONFLICT, "MSG52"),

    /**
     * The action does not exist from the plan's current status: an edit outside DRAFT, or a transition BR-32 does not
     * list. SRS section 5.3 has no text for this, so it borrows MSG43 like {@link #DATA_CONFLICT} until A-38 is
     * answered (D-66).
     */
    TRADING_PLAN_STATUS_TRANSITION_INVALID(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A MARKET plan needs the pair's current last price (BR-33) and the latest-price cache has none: the price is
     * missing, older than the cache's time to live, or the cache cannot be read (NSF-03, CR-09). 503: the same request
     * succeeds once prices flow again. SRS 5.3 has no text for this, so it borrows MSG43 until A-39 is answered (D-71).
     */
    MARKET_PRICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MSG43"),

    /**
     * The pair is already in the caller's watchlist (BR-15). MSG45 takes the symbol. 409: the request conflicts with
     * a row that exists, like {@link #DATA_CONFLICT}.
     */
    WATCHLIST_PAIR_ALREADY_WATCHED(HttpStatus.CONFLICT, "MSG45"),

    /**
     * The caller removes a watched pair that has alerts without confirming MSG26 (BR-16). MSG26 takes the symbol and the
     * number of alerts; the client asks and sends the removal again, confirmed. 400, as
     * {@link #RISK_PROFILE_CONFIRMATION_REQUIRED} (D-65).
     */
    WATCHLIST_REMOVAL_CONFIRMATION_REQUIRED(HttpStatus.BAD_REQUEST, "MSG26"),

    /**
     * The alert's status does not allow the action: pausing an alert that is not ACTIVE, resuming one that is not
     * PAUSED (SRS 3.4.3). SRS section 5.3 has no text for this, so it borrows MSG43 like
     * {@link #TRADING_PLAN_STATUS_TRANSITION_INVALID} (A-38).
     */
    ALERT_STATUS_TRANSITION_INVALID(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A paper wallet does not hold the free amount a transfer or an order needs (TR-04). SRS 5.3 has no message for
     * it yet, so it borrows MSG01; the stable code tells the client which text to show, and the message arguments
     * carry the coin, the amount needed and the amount free.
     */
    PAPER_INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "MSG01"),

    /**
     * The paper order has finished (filled, cancelled or expired), so it cannot be cancelled (TR-02). SRS 5.3 has no
     * text for this, so it borrows MSG43 like {@link #TRADING_PLAN_STATUS_TRANSITION_INVALID} (A-38). 409: the request
     * is valid and the order's current status refuses it; the response's message argument is that status.
     */
    PAPER_ORDER_NOT_OPEN(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A paper LIMIT_MAKER order would execute on arrival, taking liquidity, so it is refused and not stored, as the
     * exchange refuses it (TR-12). SRS 5.3 has no text for this, so it borrows MSG43 like {@link #PAPER_ORDER_NOT_OPEN}
     * (A-38). 409: the request is valid and the current market refuses it.
     */
    PAPER_ORDER_WOULD_TAKE(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A paper stop limit's stop is the last price itself, so it would trigger at once, and the side of the price it
     * waits on cannot be told; it is refused and not stored, as the exchange refuses it (TR-09). Borrows MSG43 like
     * {@link #PAPER_ORDER_NOT_OPEN} (A-38). 409: the request is valid and the current market refuses it.
     */
    PAPER_ORDER_WOULD_TRIGGER(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A coin of the paper Spot pair is neither USDT nor the base of an active Spot USDT pair, so what it holds could not
     * be valued in USDT (D-96); the order is refused and not stored. Borrows MSG43 like {@link #PAPER_ORDER_WOULD_TAKE}
     * (A-38); the message argument is the coin. 409: the request is valid and the market's listing refuses it.
     */
    PAPER_PAIR_NOT_VALUED(HttpStatus.CONFLICT, "MSG43"),

    /*
     * The Spot symbol filters a paper order is refused by, as the exchange refuses it rather than rounding (TR-02;
     * Binance Spot API documentation, "Filters"). 400 with MSG15, the text of a field whose value is not allowed. The
     * message arguments are the field, its value, the limit it broke and which one: MIN, MAX or STEP, so the client
     * can say "Order value must be at least 5 USDT".
     */

    /** {@code PRICE_FILTER}: a price or stopPrice off the tick, below minPrice or above maxPrice. */
    PAPER_PRICE_FILTER(HttpStatus.BAD_REQUEST, "MSG15"),

    /** {@code LOT_SIZE}: a quantity off the step, below minQty or above maxQty. */
    PAPER_LOT_SIZE(HttpStatus.BAD_REQUEST, "MSG15"),

    /** {@code MARKET_LOT_SIZE}: the quantity of a MARKET order off its step, below its minQty or above its maxQty. */
    PAPER_MARKET_LOT_SIZE(HttpStatus.BAD_REQUEST, "MSG15"),

    /** {@code NOTIONAL}: an order worth less than minNotional or more than maxNotional. */
    PAPER_NOTIONAL(HttpStatus.BAD_REQUEST, "MSG15"),

    /** {@code PERCENT_PRICE_BY_SIDE}: a limit price too far from the last price on its side. */
    PAPER_PERCENT_PRICE(HttpStatus.BAD_REQUEST, "MSG15"),

    /** {@code TRAILING_DELTA}: a trailing delta outside the symbol's range for the side its stop triggers on. */
    PAPER_TRAILING_DELTA(HttpStatus.BAD_REQUEST, "MSG15"),

    /**
     * {@code MAX_NUM_ORDERS}: the account already has as many open orders on the pair as allowed. Borrows MSG43 like
     * {@link #PAPER_ORDER_WOULD_TAKE} (A-38). 409, as {@link #PLAN_LIMIT_REACHED}: the request would succeed with
     * fewer open orders. The message arguments are {@code orders}, the count with this placement and the limit.
     */
    PAPER_MAX_NUM_ORDERS(HttpStatus.CONFLICT, "MSG43"),

    /** {@code MAX_NUM_ALGO_ORDERS}: as {@link #PAPER_MAX_NUM_ORDERS}, for the open stops of the pair. */
    PAPER_MAX_NUM_ALGO_ORDERS(HttpStatus.CONFLICT, "MSG43"),

    /*
     * Paper Futures (D-127). Each refusal also carries the exchange's own code and message in the problem detail
     * (binanceCode, binanceMsg), so a client written against the exchange's API reads it the same way.
     */

    /** A leverage outside 1 … the pair's highest ({@code -4028}). 400 with MSG15, the text of a value not allowed. */
    PAPER_LEVERAGE(HttpStatus.BAD_REQUEST, "MSG15"),

    /**
     * A lower leverage the open position cannot take: an isolated position's leverage is never lowered
     * ({@code -4161}), a cross one only while the margin still covers it ({@code -2028}). Borrows MSG43 like
     * {@link #PAPER_ORDER_WOULD_TAKE} (A-38). 409: the request is valid and the position refuses it.
     */
    PAPER_LEVERAGE_MARGIN(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The position would exceed the largest notional the leverage allows ({@code -2027}). Borrows MSG43 like
     * {@link #PAPER_ORDER_WOULD_TAKE} (A-38). 409: a lower leverage or a smaller position is accepted.
     */
    PAPER_MAX_POSITION_AT_LEVERAGE(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The margin type cannot change: it is already that type ({@code -4046}), or the pair has an open order
     * ({@code -4047}) or a position ({@code -4048}). Borrows MSG43 like {@link #PAPER_ORDER_WOULD_TAKE} (A-38).
     */
    PAPER_MARGIN_TYPE(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The position mode cannot change: it is already that mode ({@code -4059}), or the account has an open order
     * ({@code -4067}) or a position ({@code -4068}) on any pair (TR-14b). Borrows MSG43 like
     * {@link #PAPER_ORDER_WOULD_TAKE} (A-38).
     */
    PAPER_POSITION_MODE(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A cross liquidation of the account is running; orders and changes wait until it ends ({@code -2023}). Borrows
     * MSG43 like {@link #PAPER_ORDER_WOULD_TAKE} (A-38). 409: the same request succeeds once the liquidation is over.
     */
    PAPER_USER_IN_LIQUIDATION(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A Futures order costs more margin than the account has for it ({@code -2019}): the available balance for a
     * cross pair, the cross wallet's free cash for an isolated one (TR-15). Borrows MSG01 like
     * {@link #PAPER_INSUFFICIENT_BALANCE}; the message arguments carry the margin needed and the margin there is.
     */
    PAPER_INSUFFICIENT_MARGIN(HttpStatus.BAD_REQUEST, "MSG01"),

    /**
     * A reduce-only Futures order that would not make a position smaller: there is none, or it is on the same side
     * ({@code -2022}). Borrows MSG43 like {@link #PAPER_ORDER_WOULD_TAKE} (A-38). 409: the position refuses it.
     */
    PAPER_REDUCE_ONLY_REJECTED(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A Futures order's position side does not fit the position mode ({@code -4061}): one-way takes BOTH, hedge LONG
     * or SHORT. 400 with MSG15, the text of a value not allowed.
     */
    PAPER_POSITION_SIDE(HttpStatus.BAD_REQUEST, "MSG15"),

    /**
     * The margin of a position cannot change: it is a cross position ({@code -4049}), there is no position
     * ({@code -4054}), or more is taken out than leaves the initial margin covered ({@code -4051}). Borrows MSG43 like
     * {@link #PAPER_ORDER_WOULD_TAKE} (A-38). 409: the position refuses it.
     */
    PAPER_POSITION_MARGIN(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A file named for a post is not one the post may carry (BR-44): not uploaded, not the caller's, already attached
     * to another post, of a type or size outside the limits, a video too long, or a combination of files a post may
     * not hold. MSG32; the message argument is the limit that applies, e.g. {@code 5 MB}. 400: the request names it.
     */
    MEDIA_INVALID(HttpStatus.BAD_REQUEST, "MSG32"),

    /**
     * An edit drops a file of a post whose files are kept as the evidence of the moderation: the post is reported, or
     * a report on it was decided against it (BR-47, D-99). MSG56 under {@code media}; the files themselves are valid,
     * so not MSG32. 400: the request names them.
     */
    MEDIA_KEPT_AS_EVIDENCE(HttpStatus.BAD_REQUEST, "MSG56"),

    /**
     * The media storage could not be reached or is not configured, so a file could be neither signed for nor checked.
     * Borrows MSG43 (A-38). 503: the request may succeed later.
     */
    MEDIA_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MSG43"),

    /**
     * The Trader already reported this post (BR-46); nothing is stored. MSG34 in its "already reported" form. 409: the
     * request is valid and an earlier report conflicts with it.
     */
    POST_ALREADY_REPORTED(HttpStatus.CONFLICT, "MSG34"),

    /**
     * A Trader cannot report their own post (BR-46); the client offers no Report on it. Borrows MSG43 (A-38): SRS 5.3
     * has no message for it. 409: the request is valid and the post's author refuses it.
     */
    POST_REPORT_OWN_POST(HttpStatus.CONFLICT, "MSG43"),

    /**
     * Only a hidden post can be restored (UC-43); this one is published or deleted. Borrows MSG43 (A-38). 409: the
     * request is valid and the post's status refuses it.
     */
    POST_NOT_HIDDEN(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A pair or market with open simulated positions cannot be disabled (BR-59); nothing is changed. MSG40, "Pair
     * cannot be disabled", shown in line on SCR-37. 409: the request is valid and the positions refuse it.
     */
    PAIR_HAS_OPEN_POSITIONS(HttpStatus.CONFLICT, "MSG40"),

    /**
     * A market is switched on, or brackets are entered, for a pair the exchange does not trade there (NSF-01), or whose
     * filters it has not sent yet; nothing is changed. Borrows MSG43 (A-38). 409: the exchange's listing refuses it.
     */
    PAIR_NOT_TRADING(HttpStatus.CONFLICT, "MSG43"),

    /** The symbol to add is already a pair of the system (UC-45). Borrows MSG43 (A-38). 409: the pair exists. */
    PAIR_ALREADY_REGISTERED(HttpStatus.CONFLICT, "MSG43"),

    /** Another Academy lesson or term already has this slug (T-114). Borrows MSG43. 409: the slug is taken. */
    ACADEMY_SLUG_TAKEN(HttpStatus.CONFLICT, "MSG43"),

    /** Another Academy lesson already holds this place on the beginner path (T-114). Borrows MSG43. 409. */
    ACADEMY_PATH_ORDER_TAKEN(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A published Academy lesson is hidden before it is deleted, so that no reader loses it by surprise (T-114).
     * Borrows MSG43. 409: the lesson's status refuses it.
     */
    ACADEMY_ARTICLE_PUBLISHED(HttpStatus.CONFLICT, "MSG43"),

    /** An Academy topic that still holds lessons cannot be deleted (T-114). Borrows MSG43. 409: move them first. */
    ACADEMY_TOPIC_IN_USE(HttpStatus.CONFLICT, "MSG43"),

    /**
     * Another administrator changed this Academy lesson, term or topic since the editor opened it (T-114); nothing is
     * changed. Borrows MSG43. 409: the editor reloads and applies the change again.
     */
    ACADEMY_EDIT_CONFLICT(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The feed address is already the address of another news source (UC-49: the feed URL is unique), compared in its
     * normalized form; nothing is saved. Borrows MSG43 (A-38). 409: the source exists.
     */
    NEWS_SOURCE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A news source articles came from cannot be deleted, because the articles name it; it can be switched off
     * instead. Borrows MSG43 (A-38). 409: the articles refuse it.
     */
    NEWS_SOURCE_IN_USE(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The test read of a feed, done before a news source is saved (UC-49), failed: the address cannot be reached, is
     * on the server's own network, answers with an error, or does not return an RSS or Atom feed. MSG15 under
     * {@code feedUrl}, the text of a field whose value is not allowed; its own code so the console can say the feed
     * was tried. 400: the request names it.
     */
    NEWS_FEED_UNREADABLE(HttpStatus.BAD_REQUEST, "MSG15"),

    /**
     * An administrator asked to crawl a news source again within the cooldown of its last crawl (T-080b). MSG50 takes
     * the seconds left: a click repeated by mistake must not make the outlet see the crawler twice in a row.
     */
    NEWS_SOURCE_CRAWLED_RECENTLY(HttpStatus.TOO_MANY_REQUESTS, "MSG50"),

    /**
     * An administrator asked for a summary while the AI Service is not configured, cannot be reached or is rate
     * limited (T-080b). MSG31: the AI request failed; the same request may succeed later.
     */
    NEWS_SUMMARY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MSG31"),

    /** A member cannot follow themselves (D-125). Borrows MSG43 (A-38). 409: the target refuses the request. */
    FOLLOW_SELF(HttpStatus.CONFLICT, "MSG43"),

    /** A member cannot block themselves (D-125). Borrows MSG43 (A-38). 409: the target refuses the request. */
    BLOCK_SELF(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The member cannot be followed, blocked or answered by the caller (D-125): an administrator, or a member one of
     * the two blocks. Borrows MSG43 (A-38). 409: the relation between the two refuses the request.
     */
    RELATION_NOT_ALLOWED(HttpStatus.CONFLICT, "MSG43"),

    /** A Trader cannot report their own comment (D-125). Borrows MSG43 (A-38). 409: the target refuses it. */
    COMMENT_REPORT_OWN_COMMENT(HttpStatus.CONFLICT, "MSG43"),

    /** The Trader already reported this comment (D-125, as BR-46 for posts). MSG34. 409: the report exists. */
    COMMENT_ALREADY_REPORTED(HttpStatus.CONFLICT, "MSG34"),

    /** Only a hidden comment can be restored (D-125). Borrows MSG43 (A-38). 409: the comment's status refuses it. */
    COMMENT_NOT_HIDDEN(HttpStatus.CONFLICT, "MSG43"),

    /**
     * A post or comment of a banned member stays hidden (D-125): a ban is final (BR-05), so restoring their content
     * would bring a banned member back into the community. Borrows MSG43 (A-38). 409: the author refuses it.
     */
    AUTHOR_BANNED(HttpStatus.CONFLICT, "MSG43");

    private final HttpStatus status;
    private final String messageCode;

    ErrorCode(HttpStatus status, String messageCode) {
        this.status = status;
        this.messageCode = messageCode;
    }

    /** The stable code returned as the {@code code} property of the problem detail. */
    public String code() {
        return name();
    }

    /** The HTTP status of a response carrying this error. */
    public HttpStatus status() {
        return status;
    }

    /** The SRS message id (MSGxx) whose text the client displays for this error. */
    public String messageCode() {
        return messageCode;
    }
}
