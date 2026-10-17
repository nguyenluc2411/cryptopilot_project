package com.cryptopilot.paper.config;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The virtual funds a paper account is opened with, under {@code cryptopilot.paper.account} (Q-T5). They are granted
 * once, when the account opens; nothing grants them again. An invalid value stops the application at start-up.
 *
 * <h2>Coin symbols and overrides</h2>
 *
 * <p>An environment variable such as {@code CRYPTOPILOT_PAPER_ACCOUNT_SPOTGRANT_USDT} binds its map key in lower case,
 * {@code usdt}. The keys in {@code application.yml} are therefore written in lower case too, so the variable replaces
 * the configured value instead of adding a second key. The symbols are upper-cased here, as the coin table stores them.
 * Should a key still arrive in two cases, e.g. {@code USDT} from a file and {@code usdt} from the environment, the
 * lower-case one wins, being the form an environment variable binds to; the application does not stop.
 *
 * <p>The grant keeps the order it is declared in, so the INITIAL_GRANT entries are written in that order. Each amount
 * fits {@code numeric(28,8)}: at most 8 decimals and 20 integer digits, so what is stored is exactly what was
 * configured. The Futures wallet is USDⓈ-M: it is granted USDT and nothing else, as only USDT may enter it.
 *
 * <p>Rule: TR-04; Q-T5.
 *
 * @param spotGrant coin symbol to amount the Spot wallet is opened with, e.g. {@code usdt: 10000}
 * @param futuresGrant coin symbol to amount the Futures wallet is opened with; USDT only
 */
@ConfigurationProperties("cryptopilot.paper.account")
public record PaperAccountProperties(Map<String, BigDecimal> spotGrant, Map<String, BigDecimal> futuresGrant) {

    /** The margin coin of USDⓈ-M Futures. */
    public static final String FUTURES_COIN = "USDT";

    /** Decimals of an amount column, {@code numeric(28,8)}. */
    static final int AMOUNT_SCALE = 8;

    /** Integer digits of an amount column, {@code numeric(28,8)}. */
    static final int AMOUNT_INTEGER_DIGITS = 20;

    /** An exchange coin symbol: letters and digits, as the coin table stores it once upper-cased. */
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9]{1,32}");

    public PaperAccountProperties {
        spotGrant = checked(spotGrant, "spot-grant");
        futuresGrant = checked(futuresGrant, "futures-grant");
        if (!futuresGrant.keySet().equals(Set.of(FUTURES_COIN))) {
            throw new IllegalArgumentException(
                    "futures-grant may grant " + FUTURES_COIN + " only (USDⓈ-M), not " + futuresGrant.keySet());
        }
    }

    private static Map<String, BigDecimal> checked(Map<String, BigDecimal> grant, String name) {
        Objects.requireNonNull(grant, "cryptopilot.paper.account." + name + " must be declared");
        if (grant.isEmpty()) {
            throw new IllegalArgumentException(name + " must grant at least one coin");
        }
        Map<String, BigDecimal> normalised = new LinkedHashMap<>();
        Map<String, Boolean> fromLowerCaseKey = new LinkedHashMap<>();
        grant.forEach((key, amount) -> {
            String raw = key == null ? "" : key.strip();
            String symbol = raw.toUpperCase(Locale.ROOT);
            if (!SYMBOL.matcher(symbol).matches()) {
                throw new IllegalArgumentException(name + " names an invalid coin symbol '" + key + "'");
            }
            requireAmount(name + "." + symbol, amount);
            boolean lowerCase = raw.equals(raw.toLowerCase(Locale.ROOT));
            Boolean seenLowerCase = fromLowerCaseKey.get(symbol);
            // The environment's lower-case key replaces a key of another case; otherwise the first one stays.
            if (seenLowerCase == null || (lowerCase && !seenLowerCase)) {
                normalised.put(symbol, amount);
                fromLowerCaseKey.put(symbol, lowerCase);
            }
        });
        return Collections.unmodifiableMap(normalised);
    }

    private static void requireAmount(String name, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + amount);
        }
        BigDecimal exact = amount.stripTrailingZeros();
        int scale = Math.max(exact.scale(), 0);
        int integerDigits = exact.precision() - exact.scale();
        if (scale > AMOUNT_SCALE || integerDigits > AMOUNT_INTEGER_DIGITS) {
            throw new IllegalArgumentException(name + " must have at most " + AMOUNT_INTEGER_DIGITS
                    + " integer digits and " + AMOUNT_SCALE + " decimals, was " + amount.toPlainString());
        }
    }
}
