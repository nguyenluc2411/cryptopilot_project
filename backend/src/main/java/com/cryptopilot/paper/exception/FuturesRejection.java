package com.cryptopilot.paper.exception;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A paper Futures request refused by a rule the exchange has too: besides the project's stable code and SRS message,
 * the problem detail carries the exchange's own error code and message as {@code binanceCode} and {@code binanceMsg},
 * so a client written against the exchange's API branches on the same numbers.
 *
 * <p>Rule: TR-14, TR-15; D-127.
 * <p>Reference: Binance. <i>USDⓈ-M Futures API: Error Codes</i>, Binance Developer Center.
 */
public class FuturesRejection extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** The problem detail member holding the exchange's error code, a negative number. */
    public static final String BINANCE_CODE = "binanceCode";

    /** The problem detail member holding the exchange's error message. */
    public static final String BINANCE_MSG = "binanceMsg";

    private final int binanceCode;

    private final String binanceMsg;

    /**
     * @param errorCode the project's error code
     * @param binanceCode the exchange's code, e.g. {@code -4046}
     * @param binanceMsg the exchange's message, e.g. {@code No need to change margin type.}
     * @param messageArgs the values of the SRS message's placeholders, if any
     */
    public FuturesRejection(ErrorCode errorCode, int binanceCode, String binanceMsg, Object... messageArgs) {
        super(errorCode, binanceMsg, messageArgs);
        this.binanceCode = binanceCode;
        this.binanceMsg = binanceMsg;
    }

    /** The exchange's error code. */
    public int binanceCode() {
        return binanceCode;
    }

    @Override
    public Map<String, Object> problemProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(BINANCE_CODE, binanceCode);
        properties.put(BINANCE_MSG, binanceMsg);
        return properties;
    }
}
