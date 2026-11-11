package com.cryptopilot.market.model.enums;

import com.cryptopilot.market.dto.request.LeverageBracketRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ready-made leverage brackets an administrator starts from (SCR-38), and the ones a pair gets when its Futures market
 * is turned on without any (UC-45). The figures follow the shape of Binance's USDⓈ-M brackets — the leverage falls and
 * the maintenance margin rate rises as the position grows — but they are a template, not the exchange's current table:
 * its real table is behind a signed endpoint ({@code GET /fapi/v1/leverageBracket}) this system does not call, since it
 * never holds an exchange account. The maintenance amounts are not written here; BR-27 works them out on save.
 *
 * <p>Rule: UC-45, UC-46; BR-26, BR-27.
 */
public enum BracketTemplate {
    /** BTC and ETH: 125x up to 50,000 USDT, twelve brackets up to 1.8 billion. */
    MAJOR(new String[][] {
        {"50000", "125", "0.004"},
        {"600000", "100", "0.005"},
        {"3000000", "75", "0.0065"},
        {"12000000", "50", "0.01"},
        {"70000000", "25", "0.02"},
        {"100000000", "20", "0.025"},
        {"230000000", "10", "0.05"},
        {"480000000", "5", "0.1"},
        {"600000000", "4", "0.125"},
        {"800000000", "3", "0.15"},
        {"1200000000", "2", "0.25"},
        {"1800000000", "1", "0.5"}
    }),
    /** A large altcoin: 75x up to 5,000 USDT, ten brackets up to 30 million. */
    ALTCOIN(new String[][] {
        {"5000", "75", "0.005"},
        {"25000", "50", "0.01"},
        {"100000", "25", "0.02"},
        {"250000", "20", "0.025"},
        {"1000000", "10", "0.05"},
        {"3000000", "5", "0.1"},
        {"5000000", "4", "0.125"},
        {"10000000", "3", "0.15"},
        {"20000000", "2", "0.25"},
        {"30000000", "1", "0.5"}
    }),
    /** A small or volatile coin: 50x up to 5,000 USDT, eight brackets up to 5 million. */
    SMALL_CAP(new String[][] {
        {"5000", "50", "0.01"},
        {"25000", "20", "0.025"},
        {"100000", "10", "0.05"},
        {"250000", "5", "0.1"},
        {"1000000", "4", "0.125"},
        {"2000000", "3", "0.15"},
        {"3000000", "2", "0.25"},
        {"5000000", "1", "0.5"}
    });

    /** The coins whose pairs get {@link #MAJOR} when they are turned on without brackets. */
    private static final Set<String> MAJOR_COINS = Set.of("BTC", "ETH");

    private final String[][] rows;

    BracketTemplate(String[][] rows) {
        this.rows = rows;
    }

    /** The template as the rows of a bracket request: each starts where the one before ends, amounts left out. */
    public List<LeverageBracketRequest> rows() {
        List<LeverageBracketRequest> requests = new ArrayList<>();
        BigDecimal floor = BigDecimal.ZERO;
        for (String[] row : rows) {
            BigDecimal cap = new BigDecimal(row[0]);
            requests.add(new LeverageBracketRequest(floor, cap, Integer.valueOf(row[1]), new BigDecimal(row[2]), null));
            floor = cap;
        }
        return requests;
    }

    /** The highest leverage of the template, its first bracket's. */
    public int firstLeverage() {
        return Integer.parseInt(rows[0][1]);
    }

    /**
     * The template a pair gets when its Futures market is turned on without brackets: {@link #MAJOR} for BTC and
     * ETH, {@link #ALTCOIN} otherwise; and whichever is chosen, one whose first bracket allows the pair's ceiling
     * (BR-26), so the new brackets never refuse the leverage the administrator has just set.
     */
    public static BracketTemplate defaultFor(String baseCoin, int ceiling) {
        BracketTemplate chosen = MAJOR_COINS.contains(baseCoin) ? MAJOR : ALTCOIN;
        return ceiling > chosen.firstLeverage() ? MAJOR : chosen;
    }
}
