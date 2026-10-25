package com.cryptopilot.journal.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.journal.dto.response.JournalEntryResponse;
import com.cryptopilot.journal.model.JournalListQuery;
import java.util.UUID;

/**
 * The Trader's journal as it is read (SCR-20, UC-22). Implemented by
 * {@link com.cryptopilot.journal.service.impl.JournalServiceImpl}.
 *
 * <p>Rule: SRS 3.6.1; UC-22; BR-38; CR-04.
 */
public interface JournalService {

    /**
     * One page of the user's trades, the latest entry first; only the user's own records are read.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException naming {@code page} or {@code pageSize} with
     *     MSG15
     */
    PageResponse<JournalEntryResponse> list(UUID userId, JournalListQuery query);
}
