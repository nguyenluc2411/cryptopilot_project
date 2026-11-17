package com.cryptopilot.community.service;

import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import java.util.List;

/**
 * The use case of {@link com.cryptopilot.community.service.impl.CoinSentimentServiceImpl}: the community's leaning per
 * coin, served from the last computed answer (D-48).
 *
 * <p>Rule: D-125.
 */
public interface CoinSentimentService {

    /** The last computed leaning per coin; computed on the spot only before the first refresh. */
    List<CoinSentimentResponse> current();

    /** Computes it again; a failure keeps the previous answer. A refresh already running is not doubled. */
    void refresh();
}
