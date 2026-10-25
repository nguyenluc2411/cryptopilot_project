package com.cryptopilot.schema;

import com.cryptopilot.market.dto.request.LeverageBracketRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WF-01 demo data file {@code deploy/demo/wf01.json} that {@code deploy/demo/load-wf01.ps1} loads, read the way the
 * tests compare it with the database. The build runs in {@code backend/}, so the file is one directory up.
 *
 * <p>Rule: DEMO-01; BR-26, BR-27, BR-62.
 */
final class DemoWf01Data {

    /** The data file of the loader. */
    static final Path FILE = Path.of("..", "deploy", "demo", "wf01.json");

    /** The loader itself. */
    static final Path SCRIPT = Path.of("..", "deploy", "demo", "load-wf01.ps1");

    private final JsonNode root;

    private DemoWf01Data(JsonNode root) {
        this.root = root;
    }

    static DemoWf01Data read() {
        try {
            return new DemoWf01Data(JsonMapper.builder().build().readTree(Files.readString(FILE)));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read " + FILE.toAbsolutePath(), failure);
        }
    }

    /** The symbols of the pairs, in the file's order. */
    List<String> symbols() {
        List<String> symbols = new ArrayList<>();
        root.get("pairs").forEach(pair -> symbols.add(pair.get("symbol").asString()));
        return symbols;
    }

    /** The pair's leverage ceiling. */
    int maxLeverage(String symbol) {
        return pair(symbol).get("maxLeverage").asInt();
    }

    /** The pair's brackets as the administrator's request carries them, maintenance amount included. */
    List<LeverageBracketRequest> brackets(String symbol) {
        List<LeverageBracketRequest> rows = new ArrayList<>();
        pair(symbol)
                .get("brackets")
                .forEach(row -> rows.add(new LeverageBracketRequest(
                        decimal(row, "notionalFloor"),
                        decimal(row, "notionalCap"),
                        row.get("maxLeverage").asInt(),
                        decimal(row, "maintenanceMarginRate"),
                        decimal(row, "maintenanceAmount"))));
        return rows;
    }

    /** The {@code freePlans} block: how many ACTIVE plans demo.free gets, and how they are placed. */
    JsonNode freePlans() {
        return root.get("freePlans");
    }

    /** The whole file, for a test that writes a changed copy. */
    JsonNode root() {
        return root;
    }

    private JsonNode pair(String symbol) {
        for (JsonNode pair : root.get("pairs")) {
            if (pair.get("symbol").asString().equals(symbol)) {
                return pair;
            }
        }
        throw new IllegalArgumentException(symbol + " is not in " + FILE);
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        return new BigDecimal(row.get(field).asString());
    }
}
