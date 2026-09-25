package com.cryptopilot.market.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.dto.response.PairResponse;

/**
 * The pairs an administrator enabled on a market, for the market list (UC-09); implemented by
 * {@link com.cryptopilot.market.service.impl.PairServiceImpl}.
 *
 * <p>Rule: UC-09, BR-07; SRS 3.3.1; D-48.
 */
public interface PairService {

    /**
     * One page of the pairs enabled on a market (BR-07), in the administrator's display order, then by symbol.
     *
     * @param market {@code spot} or {@code futures}, in any case
     * @param page the page, from 1; {@code null} for the first
     * @param pageSize the page size, 1 to 100; {@code null} for 20
     * @throws com.cryptopilot.common.exception.BusinessException {@code VALIDATION_FAILED} (MSG01) for an unknown
     *     market or a page out of range
     */
    PageResponse<PairResponse> enabledPairs(String market, Integer page, Integer pageSize);
}
