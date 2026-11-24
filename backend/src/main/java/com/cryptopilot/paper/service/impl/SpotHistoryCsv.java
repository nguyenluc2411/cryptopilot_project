package com.cryptopilot.paper.service.impl;

import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Spot order and trade history as CSV files (B7), as the exchange exports them: a header line, then one line per
 * row, each ending in CRLF (RFC 4180). Instants are ISO 8601 in UTC, amounts are plain decimals, an absent value is an
 * empty cell. A cell that a spreadsheet would read as a formula gets a leading apostrophe (OWASP CSV injection), as
 * the audit log export does.
 *
 * <p>Rule: B7 of paper-trading-plan section 20.
 */
final class SpotHistoryCsv {

    static final String ORDER_HEADER = "placedAt,orderId,clientOrderId,symbol,side,type,timeInForce,price,stopPrice,"
            + "trailingDelta,quantity,quoteOrderQty,executedQuantity,cumQuote,avgPrice,status,statusReason,triggeredAt,"
            + "closedAt,listId";

    static final String FILL_HEADER =
            "tradedAt,fillId,orderId,symbol,side,price,quantity,quoteAmount,fee,feeAsset,liquidity,source";

    private static final String LINE_END = "\r\n";

    private SpotHistoryCsv() {}

    static String orders(List<OrderResponse> orders) {
        return file(
                ORDER_HEADER,
                orders.stream()
                        .map(order -> line(
                                order.placedAt(),
                                order.orderId(),
                                order.clientOrderId(),
                                order.symbol(),
                                order.side(),
                                order.type(),
                                order.timeInForce(),
                                order.price(),
                                order.stopPrice(),
                                order.trailingDelta(),
                                order.quantity(),
                                order.quoteOrderQty(),
                                order.executedQuantity(),
                                order.cumQuote(),
                                order.avgPrice(),
                                order.status(),
                                order.statusReason(),
                                order.triggeredAt(),
                                order.closedAt(),
                                order.listId())));
    }

    static String fills(List<FillResponse> fills) {
        return file(
                FILL_HEADER,
                fills.stream()
                        .map(fill -> line(
                                fill.tradedAt(),
                                fill.fillId(),
                                fill.orderId(),
                                fill.symbol(),
                                fill.side(),
                                fill.price(),
                                fill.quantity(),
                                fill.quoteAmount(),
                                fill.fee(),
                                fill.feeAsset(),
                                fill.liquidity(),
                                fill.source())));
    }

    private static String file(String header, Stream<String> lines) {
        return header + LINE_END + lines.map(line -> line + LINE_END).collect(Collectors.joining());
    }

    private static String line(Object... values) {
        return Stream.of(values).map(SpotHistoryCsv::cell).collect(Collectors.joining(","));
    }

    /** One value as a cell: quoted when it holds a separator, a quote or a line break; never read as a formula. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof BigDecimal amount
                ? amount.stripTrailingZeros().toPlainString()
                : value instanceof Instant instant ? instant.toString() : Objects.toString(value);
        if (text.isEmpty()) {
            return "";
        }
        char first = text.charAt(0);
        boolean number = value instanceof BigDecimal || value instanceof Number;
        if (!number
                && (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r')) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
