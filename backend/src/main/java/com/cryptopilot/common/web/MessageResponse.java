package com.cryptopilot.common.web;

/**
 * The answer to a request that succeeded and has nothing to return but the fact that it did.
 *
 * <p>It carries the SRS message code and not the message. The text of section 5.3 lives in the
 * client, which chooses it by code and substitutes whatever the sentence needs — MSG05 names the
 * address, which the client already has because it just sent it.
 *
 * <p>It lives in the shared kernel because more than one module answers this way — {@code auth}
 * with MSG05, MSG06, MSG12 and MSG13, {@code user} with MSG14 — and a module may not reach another
 * module's {@code dto} package. It says nothing about any one of them, which is what lets it sit
 * here.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1; SRS section 5.3.
 *
 * @param messageCode the id of the message to display, such as {@code MSG05}
 */
public record MessageResponse(String messageCode) {}
