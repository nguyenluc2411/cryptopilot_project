package com.cryptopilot.paper.repository;

import com.cryptopilot.market.OpenPositionCheck;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The open positions of the demo trading accounts on a pair (BR-59). Only the futures market holds positions; a Spot
 * account holds balances, which a disabled pair leaves where they are.
 *
 * <p>Rule: BR-59; TR-02.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperOpenPositions implements OpenPositionCheck {

    private final JdbcClient sql;

    @Override
    public boolean anyOpen(UUID pairId, MarketType market) {
        if (market != MarketType.FUTURES) {
            return false;
        }
        return sql.sql("select exists (select 1 from paper_position where pair_id = ? and position_status = 'OPEN')")
                .param(pairId)
                .query(Boolean.class)
                .single();
    }
}
