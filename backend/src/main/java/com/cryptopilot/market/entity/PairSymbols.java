package com.cryptopilot.market.entity;

import java.util.regex.Pattern;

/**
 * The spelling rules a coin or pair symbol must follow before it is stored: upper-case letters and
 * digits only, as Binance spells them, and no longer than its column.
 *
 * <p>Upper case is required rather than applied: the exchange never sends lower case, so a lower-case
 * symbol arriving here is a defect in the caller, and correcting it silently would hide that.
 */
final class PairSymbols {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9]+");

    private PairSymbols() {}

    static String require(String symbol, String field, int maxLength) {
        String value = requireText(symbol, field, maxLength);
        if (!SYMBOL.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be upper-case letters and digits, was " + value);
        }
        return value;
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is longer than " + maxLength + " characters");
        }
        return value;
    }
}
