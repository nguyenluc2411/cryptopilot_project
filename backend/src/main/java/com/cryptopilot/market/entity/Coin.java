package com.cryptopilot.market.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * An asset a pair is made of — {@code BTC}, {@code USDT} — and what the forum and the news tag.
 *
 * <p>Its own aggregate: a coin is referenced by pairs as base or quote, by forum posts and by news
 * articles, and outlives all of them, so none of them owns it. It is identified by its symbol, unique in
 * the schema ({@code uq_coin_symbol}); the key is a UUID so that renaming a coin's display name touches
 * one row.
 *
 * <p>The symbol is stored as the exchange spells it, upper case. The name defaults to the symbol when a
 * coin first arrives from the exchange, which sends no names; CoinPaprika fills in the name and the logo later
 * ({@link #describe}), and never overwrites one already set.
 *
 * <p>Rule: BR-07; SRS 3.1.5; TECHNICAL_DESIGN 6.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 (an entity is
 * defined by a thread of identity that runs through time, not by its attributes).
 */
@Getter
@Entity
@Table(name = "coin")
@AttributeOverride(name = "id", column = @Column(name = "coin_id", nullable = false, updatable = false))
public class Coin extends BaseEntity {

    /** Longest symbol the column holds ({@code varchar(32)}). */
    static final int SYMBOL_LENGTH = 32;

    /** Longest name the column holds ({@code varchar(100)}). */
    static final int NAME_LENGTH = 100;

    /** Longest logo address the column holds ({@code varchar(500)}). */
    static final int LOGO_URL_LENGTH = 500;

    /** The asset as the exchange spells it, e.g. {@code BTC}. */
    @Column(name = "symbol", nullable = false, length = SYMBOL_LENGTH, updatable = false)
    private String symbol;

    /** The name shown beside the symbol. */
    @Column(name = "coin_name", nullable = false, length = NAME_LENGTH)
    private String coinName;

    /** Where the logo image is, or {@code null}. */
    @Column(name = "logo_url", length = LOGO_URL_LENGTH)
    private String logoUrl;

    /** For JPA only. */
    protected Coin() {}

    private Coin(String symbol, String coinName) {
        this.symbol = PairSymbols.require(symbol, "symbol", SYMBOL_LENGTH);
        this.coinName = PairSymbols.requireText(coinName, "coinName", NAME_LENGTH);
    }

    /**
     * A coin as it first arrives from the exchange, whose name is not known yet and defaults to the
     * symbol.
     */
    public static Coin fromExchange(String symbol) {
        return new Coin(symbol, symbol);
    }

    /**
     * Fills in what is still missing from a directory's description, and never overwrites: a name is taken only while
     * the coin is still named after its symbol, a logo only while it has none. A value too long for its column, blank,
     * or a logo that is not an https address is ignored.
     *
     * @return whether anything changed
     */
    public boolean describe(String name, String logo) {
        boolean changed = false;
        if (coinName.equals(symbol) && name != null) {
            String candidate = name.strip();
            if (!candidate.isEmpty() && candidate.length() <= NAME_LENGTH && !candidate.equals(coinName)) {
                coinName = candidate;
                changed = true;
            }
        }
        if (logoUrl == null && logo != null) {
            String candidate = logo.strip();
            if (candidate.startsWith("https://") && candidate.length() <= LOGO_URL_LENGTH) {
                logoUrl = candidate;
                changed = true;
            }
        }
        return changed;
    }
}
