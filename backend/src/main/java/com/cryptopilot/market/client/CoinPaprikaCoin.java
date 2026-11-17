package com.cryptopilot.market.client;

/**
 * One coin of CoinPaprika's {@code /coins}: its id, ticker, name and rank by market cap.
 *
 * @param id CoinPaprika's id, e.g. {@code btc-bitcoin}
 * @param symbol the ticker, upper case, e.g. {@code BTC}
 * @param name the display name, e.g. {@code Bitcoin}
 * @param rank the rank by market cap, 1 the largest; 0 for a coin without one
 * @param active whether CoinPaprika still tracks it
 */
public record CoinPaprikaCoin(String id, String symbol, String name, int rank, boolean active) {

    /** Whether it can stand for a listed coin: tracked and ranked. */
    public boolean ranked() {
        return active && rank > 0;
    }
}
