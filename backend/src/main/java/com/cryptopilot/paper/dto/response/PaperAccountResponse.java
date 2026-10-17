package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.PositionMode;
import java.time.Instant;
import java.util.UUID;

/**
 * The caller's paper account.
 *
 * @param accountId the account
 * @param positionMode how Futures positions are held (Q-T4)
 * @param openedAt when the account was opened and its virtual funds granted
 */
public record PaperAccountResponse(UUID accountId, PositionMode positionMode, Instant openedAt) {}
