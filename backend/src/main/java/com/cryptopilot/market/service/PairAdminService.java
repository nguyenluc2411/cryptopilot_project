package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.request.LeverageBracketsRequest;
import com.cryptopilot.market.dto.request.UpdatePairRequest;
import com.cryptopilot.market.dto.response.AdminPairResponse;
import com.cryptopilot.market.dto.response.ExchangeSymbolResponse;
import com.cryptopilot.market.dto.response.LeverageBracketsResponse;
import com.cryptopilot.market.model.enums.PairStatus;
import java.util.List;
import java.util.UUID;

/**
 * The administration of supported pairs and their leverage brackets (SCR-37, SCR-38; UC-45, UC-46). Every change is
 * audited with its old and new values (BR-57). Implemented by
 * {@link com.cryptopilot.market.service.impl.PairAdminServiceImpl}.
 *
 * <p>Rule: UC-45, UC-46; BR-07, BR-26, BR-27, BR-31, BR-57, BR-59; NSF-01.
 */
public interface PairAdminService {

    /**
     * The pairs of the system, in display order, then by symbol.
     *
     * @param query only symbols containing this, case ignored, or {@code null}
     * @param status only pairs of this status, or {@code null}
     */
    List<AdminPairResponse> list(String query, PairStatus status);

    /** One pair; MSG41 when unknown. */
    AdminPairResponse get(UUID pairId);

    /**
     * The symbols the exchange trades and the system does not have yet, read from the exchange now (NSF-01), by
     * symbol, at most 50.
     *
     * @param query only symbols containing this, case ignored, or {@code null}
     */
    List<ExchangeSymbolResponse> exchangeSymbols(String query);

    /**
     * Adds a symbol the exchange trades, INACTIVE with both markets off and its filters taken from the exchange,
     * placed last in lists (UC-45).
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAIR_ALREADY_REGISTERED}, or
     *     {@code PAIR_NOT_TRADING} when the exchange trades it on no market
     */
    AdminPairResponse register(UUID adminId, String symbol);

    /**
     * Replaces the switches, the leverage ceiling and the display order (UC-45). A market switched on must be one the
     * exchange trades; a market taken out of service must hold no open simulated position, and its ACTIVE plans are
     * cancelled with the change (BR-59).
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAIR_HAS_OPEN_POSITIONS} (MSG40) or
     *     {@code PAIR_NOT_TRADING}
     */
    AdminPairResponse update(UUID adminId, UUID pairId, UpdatePairRequest request);

    /** The pair's leverage brackets (SCR-38); MSG41 when the pair is unknown. */
    LeverageBracketsResponse brackets(UUID pairId);

    /**
     * Replaces the pair's leverage brackets (UC-46): contiguous from 0, non-overlapping, the maintenance margin rate
     * between 0 and 1, the maximum leverage never rising with the bracket number, and the maintenance amount the one
     * that keeps the maintenance margin continuous (BR-27). New calculations use them; stored plans keep their
     * snapshot (BR-31).
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException naming each rejected row field
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAIR_NOT_TRADING} when the exchange has never
     *     listed the pair on futures
     */
    LeverageBracketsResponse replaceBrackets(UUID adminId, UUID pairId, LeverageBracketsRequest request);
}
