package com.cryptopilot.paper.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.paper.model.FuturesAccountMargin;
import com.cryptopilot.paper.model.IsolatedCashFlow;
import com.cryptopilot.paper.model.PendingOrderMargin;
import com.cryptopilot.paper.model.PositionFill;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the shared Futures golden cases of {@code tools/golden/futures} against {@link FuturesMarginMath}. The web
 * client runs the same files against its own copy of the arithmetic, so both sides show the same numbers.
 *
 * <p>Rule: TR-40, TR-45; D-127.
 */
class FuturesGoldenTest {

    private static final Path GOLDEN = Path.of("..", "tools", "golden", "futures");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> TR45_everyGoldenCase_matchesTheArithmetic() throws IOException {
        Map<String, List<LeverageTier>> brackets = brackets();
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(GOLDEN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("brackets.json"))
                    .sorted()
                    .toList()) {
                for (JsonNode c : JSON.readTree(file.toFile()).get("cases")) {
                    tests.add(DynamicTest.dynamicTest(
                            c.get("id").asString() + " " + c.get("fn").asString(), () -> run(c, brackets)));
                }
            }
        }
        return tests.stream();
    }

    /** Guards the runner itself: an empty folder would pass without testing anything. */
    @Test
    void TR45_theGoldenFolder_hasCasesForEveryFunction() throws IOException {
        List<String> functions = new ArrayList<>();
        try (Stream<Path> files = Files.list(GOLDEN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("brackets.json"))
                    .toList()) {
                JSON.readTree(file.toFile())
                        .get("cases")
                        .forEach(c -> functions.add(c.get("fn").asString()));
            }
        }
        assertThat(functions)
                .contains(
                        "bracket",
                        "maintenanceMargin",
                        "maxNotionalAtLeverage",
                        "unrealizedPnl",
                        "applyFill",
                        "orderCost",
                        "isolatedFillMargin",
                        "openOrderInitialMargin",
                        "accountMargin",
                        "maxOpenQuantity",
                        "maxRemovableMargin",
                        "marginRatio",
                        "liquidationPrice",
                        "closeFlow",
                        "fundingFlow",
                        "fundingFee",
                        "liquidationFee");
    }

    private static void run(JsonNode c, Map<String, List<LeverageTier>> brackets) {
        JsonNode in = c.get("input");
        JsonNode expect = c.get("expect");
        BigDecimal tolerance = c.has("tolerance") ? dec(c.get("tolerance")) : BigDecimal.ZERO;
        if (c.path("error").asBoolean(false)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> evaluate(c.get("fn").asString(), in, brackets));
            return;
        }
        Map<String, Object> actual = evaluate(c.get("fn").asString(), in, brackets);
        expect.properties().forEach(entry -> {
            Object value = actual.get(entry.getKey());
            JsonNode wanted = entry.getValue();
            String label = c.get("id").asString() + "." + entry.getKey();
            if (wanted.isNull()) {
                assertThat(value).as(label).isNull();
            } else if (wanted.isNumber()) {
                assertThat(value).as(label).isEqualTo(wanted.asInt());
            } else if (value instanceof BigDecimal number) {
                assertThat(number.subtract(dec(wanted)).abs())
                        .as(label + " = " + number + ", expected " + wanted.asString())
                        .isLessThanOrEqualTo(tolerance);
            } else {
                assertThat(String.valueOf(value)).as(label).isEqualTo(wanted.asString());
            }
        });
    }

    private static Map<String, Object> evaluate(String fn, JsonNode in, Map<String, List<LeverageTier>> brackets) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (fn) {
            case "bracket" -> {
                Optional<LeverageTier> tier =
                        FuturesMarginMath.bracketFor(tiers(in, brackets), dec(in.get("notional")));
                out.put("bracketNo", tier.map(LeverageTier::bracketNo).orElse(null));
                out.put(
                        "maintenanceAmount",
                        tier.map(LeverageTier::maintenanceAmount).orElse(null));
            }
            case "maintenanceMargin" ->
                out.put("value", FuturesMarginMath.maintenanceMargin(tiers(in, brackets), dec(in.get("notional"))));
            case "maxNotionalAtLeverage" ->
                out.put(
                        "value",
                        FuturesMarginMath.maxNotionalAtLeverage(
                                tiers(in, brackets), in.get("leverage").asInt()));
            case "unrealizedPnl" ->
                out.put(
                        "value",
                        FuturesMarginMath.unrealizedPnl(
                                direction(in),
                                dec(in.get("quantity")),
                                dec(in.get("entryPrice")),
                                dec(in.get("markPrice"))));
            case "applyFill" -> {
                PositionFill fill = FuturesMarginMath.applyFill(
                        dec(in.get("quantityBefore")),
                        dec(in.get("entryPriceBefore")),
                        side(in),
                        dec(in.get("quantity")),
                        dec(in.get("price")));
                out.put("closedQuantity", fill.closedQuantity());
                out.put("realizedPnl", fill.realizedPnl());
                out.put("openedQuantity", fill.openedQuantity());
                out.put("quantityAfter", fill.quantityAfter());
                out.put("entryPriceAfter", fill.entryPriceAfter());
            }
            case "orderCost" ->
                out.put(
                        "value",
                        FuturesMarginMath.orderCost(
                                side(in),
                                dec(in.get("quantity")),
                                dec(in.get("limitPrice")),
                                dec(in.get("lastPrice")),
                                dec(in.get("markPrice")),
                                in.get("leverage").asInt()));
            case "isolatedFillMargin" ->
                out.put(
                        "value",
                        FuturesMarginMath.isolatedFillMargin(
                                direction(in),
                                dec(in.get("quantity")),
                                dec(in.get("fillPrice")),
                                dec(in.get("markPrice")),
                                in.get("leverage").asInt()));
            case "openOrderInitialMargin" -> {
                List<PendingOrderMargin> orders = new ArrayList<>();
                in.get("orders")
                        .forEach(o -> orders.add(new PendingOrderMargin(
                                OrderSide.valueOf(o.get("side").asString()),
                                dec(o.get("quantity")),
                                dec(o.get("cost")))));
                out.put("value", FuturesMarginMath.openOrderInitialMargin(dec(in.get("positionQuantity")), orders));
            }
            case "accountMargin" -> {
                FuturesAccountMargin m = FuturesMarginMath.accountMargin(
                        dec(in.get("crossWalletBalance")), dec(in.get("crossUnrealizedPnl")),
                        dec(in.get("crossPositionInitialMargin")), dec(in.get("openOrderInitialMargin")));
                out.put("availableBalance", m.availableBalance());
                out.put("crossFreeCash", m.crossFreeCash());
                out.put("maxWithdrawAmount", m.maxWithdrawAmount());
            }
            case "maxOpenQuantity" -> {
                int leverage = in.get("leverage").asInt();
                out.put(
                        "value",
                        FuturesMarginMath.maxOpenQuantity(
                                side(in),
                                dec(in.get("limitPrice")),
                                dec(in.get("lastPrice")),
                                dec(in.get("markPrice")),
                                leverage,
                                dec(in.get("budget")),
                                dec(in.get("positionQuantity")),
                                FuturesMarginMath.maxNotionalAtLeverage(tiers(in, brackets), leverage),
                                dec(in.get("stepSize"))));
            }
            case "maxRemovableMargin" ->
                out.put(
                        "value",
                        FuturesMarginMath.maxRemovableMargin(
                                dec(in.get("isolatedWallet")), dec(in.get("unrealizedPnl")),
                                dec(in.get("markNotional")), in.get("leverage").asInt()));
            case "marginRatio" ->
                out.put(
                        "value",
                        FuturesMarginMath.marginRatio(dec(in.get("maintenanceMargin")), dec(in.get("marginBalance")))
                                .orElse(null));
            case "liquidationPrice" ->
                out.put(
                        "value",
                        FuturesMarginMath.liquidationPrice(
                                direction(in),
                                dec(in.get("quantity")),
                                dec(in.get("entryPrice")),
                                dec(in.get("walletBalance")),
                                dec(in.get("otherMaintenanceMargin")),
                                dec(in.get("otherUnrealizedPnl")),
                                tiers(in, brackets),
                                dec(in.get("tickSize"))));
            case "closeFlow" ->
                putFlow(
                        out,
                        FuturesMarginMath.closeFlow(
                                dec(in.get("isolatedWallet")),
                                dec(in.get("closedQuantity")),
                                dec(in.get("positionQuantity")),
                                dec(in.get("realizedPnl")),
                                dec(in.get("fee"))));
            case "fundingFlow" ->
                putFlow(out, FuturesMarginMath.fundingFlow(dec(in.get("isolatedWallet")), dec(in.get("fundingFee"))));
            case "fundingFee" ->
                out.put(
                        "value",
                        FuturesMarginMath.fundingFee(
                                direction(in),
                                dec(in.get("quantity")),
                                dec(in.get("markPrice")),
                                dec(in.get("fundingRate"))));
            case "liquidationFee" ->
                out.put(
                        "value",
                        FuturesMarginMath.liquidationFee(
                                dec(in.get("notional")), dec(in.get("feeRate")), dec(in.get("remaining"))));
            default -> throw new IllegalStateException("unknown golden function " + fn);
        }
        return out;
    }

    private static void putFlow(Map<String, Object> out, IsolatedCashFlow flow) {
        out.put("branch", flow.branch().name());
        out.put("isolatedWalletChange", flow.isolatedWalletChange());
        out.put("crossChange", flow.crossChange());
        out.put("insuranceCover", flow.insuranceCover());
    }

    private static Map<String, List<LeverageTier>> brackets() throws IOException {
        Map<String, List<LeverageTier>> sets = new LinkedHashMap<>();
        JSON.readTree(GOLDEN.resolve("brackets.json").toFile()).properties().forEach(set -> {
            List<LeverageTier> tiers = new ArrayList<>();
            set.getValue()
                    .forEach(t -> tiers.add(new LeverageTier(
                            t.get("bracketNo").asInt(),
                            dec(t.get("notionalFloor")),
                            dec(t.get("notionalCap")),
                            t.get("maxLeverage").asInt(),
                            dec(t.get("maintenanceMarginRate")),
                            dec(t.get("maintenanceAmount")))));
            sets.put(set.getKey(), List.copyOf(tiers));
        });
        return sets;
    }

    private static List<LeverageTier> tiers(JsonNode in, Map<String, List<LeverageTier>> brackets) {
        return brackets.get(in.get("brackets").asString());
    }

    private static PositionDirection direction(JsonNode in) {
        return PositionDirection.valueOf(in.get("direction").asString());
    }

    private static OrderSide side(JsonNode in) {
        return OrderSide.valueOf(in.get("side").asString());
    }

    private static BigDecimal dec(JsonNode node) {
        return node == null || node.isNull() ? null : new BigDecimal(node.asString());
    }
}
