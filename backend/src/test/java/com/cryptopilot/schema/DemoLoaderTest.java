package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.dto.request.LeverageBracketRequest;
import com.cryptopilot.market.dto.request.LeverageBracketsRequest;
import com.cryptopilot.market.dto.request.UpdatePairRequest;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.PairStatus;
import com.cryptopilot.market.service.PairAdminService;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.support.OwnDatabaseConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.service.TradingPlanService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The WF-01 loader {@code deploy/demo/load-wf01.ps1} run for real (DEMO-01), the way staging runs it: a backend on the
 * {@code demo} profile — so Flyway applies the demo accounts — and the script calling its API as the administrator and
 * as demo.free. What NSF-01 and NSF-03 would provide is written by the test instead: BTCUSDT and ETHUSDT as the
 * exchange lists them, switched off, and a Spot price for each.
 *
 * <p>The backend runs on a database of its own on the shared container, like {@link DemoDatasetTest}: the demo set
 * must never reach the database the rest of the suite shares. The script needs PowerShell, {@code pwsh} (the CI runner
 * has it) or Windows PowerShell.
 *
 * <p>It also drives demo scenario DS-01c through the API on the brackets the script loaded: a 50x Futures plan whose
 * stop loss lies beyond its liquidation price is blocked and its activation refused with MSG18.
 *
 * <p>Rule: DEMO-01; BR-26, BR-27, BR-28, BR-62 (ACTIVE_PLAN_MAX), UC-16, UC-18, UC-45, UC-46; MSG18.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = OwnDatabaseConfig.PROPERTY + "=" + DemoLoaderTest.LOADER_DATABASE)
@ActiveProfiles("demo")
@Import({TestcontainersConfig.class, OwnDatabaseConfig.class})
class DemoLoaderTest {

    static final String LOADER_DATABASE = "cryptopilot_demo_loader";
    private static final String ADMIN_PASSWORD = "admin-password-for-the-loader-test";
    private static final String DEMO_PASSWORD = "demo-password-for-the-loader-test";
    private static final UUID ADMIN_ID = UUID.fromString("019b76da-a800-7000-8000-000000000001");
    private static final UUID DEMO_FREE = UUID.fromString("019b76da-a800-7d04-8000-000000000001");
    private static final BigDecimal BTC_PRICE = new BigDecimal("100000.00");
    private static final BigDecimal ETH_PRICE = new BigDecimal("4000.00");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private PairAdminService pairAdmin;

    @Autowired
    private TradingPlanService plans;

    @TempDir
    Path temporary;

    private final DemoWf01Data data = DemoWf01Data.read();
    private UUID btc;
    private UUID eth;

    /**
     * The state of a staging database right after start-up: the demo accounts of Flyway with the passwords the
     * environment gave, both pairs listed by the exchange and switched off, a Spot price for each, no plan.
     */
    @BeforeEach
    void freshlyStartedBackend() {
        assertThat(sql.sql("select current_database()").query(String.class).single())
                .as("the backend runs on its own database, never the one the suite shares")
                .isEqualTo(LOADER_DATABASE);
        // A plan filled at activation opens its journal row (D-118), which points at the plan.
        sql.sql("delete from trading_journal").update();
        sql.sql("delete from trading_plan").update();
        sql.sql("delete from leverage_bracket").update();
        sql.sql("delete from audit_log").update();
        MarketTestData market = new MarketTestData(sql, Instant.now());
        market.clear();
        sql.sql("update user_account set password_hash = ? where email = 'admin@cryptopilot.invalid'")
                .param(passwords.encode(ADMIN_PASSWORD))
                .update();
        sql.sql("update user_account set password_hash = ? where email like 'demo.%@cryptopilot.invalid'")
                .param(passwords.encode(DEMO_PASSWORD))
                .update();

        btc = market.pair("BTCUSDT", false, false, "TRADING", "TRADING", 1);
        eth = market.pair("ETHUSDT", false, false, "TRADING", "TRADING", 2);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.00001, spot_min_notional = 5,
                               futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 100""").update();
        price(btc, "BTCUSDT", BTC_PRICE);
        price(eth, "ETHUSDT", ETH_PRICE);
    }

    @Test
    void DEMO01_loadingTwice_setsTheDemoUpOnceAndThenChangesNothing() {
        Run first = load();

        assertThat(first.exitCode()).as(first.output()).isZero();
        assertPairsAsInTheDataFile();
        List<Map<String, Object>> free = activePlansOfDemoFree();
        assertThat(free).hasSize(3);
        assertThat(free).allSatisfy(plan -> assertThat(plan)
                .containsEntry("market_type", "SPOT")
                .containsEntry("direction", "LONG")
                .containsEntry("entry_type", "LIMIT")
                .containsEntry("plan_note", data.freePlans().get("note").asString()));
        assertThat(free)
                .extracting(plan -> ((Timestamp) plan.get("expires_at")).toInstant())
                .containsOnly(Instant.parse("2027-04-01T00:00:00Z"));
        assertThat(free)
                .extracting(plan -> plan.get("symbol") + " " + cents(plan, "entry_price") + " "
                        + cents(plan, "stop_loss_price") + " " + cents(plan, "take_profit_price"))
                .as("half, 45 % and 150 % of the Spot price, on the tick: far from the price, and the TP above it")
                .containsExactlyInAnyOrder(
                        "BTCUSDT 50000.00 45000.00 150000.00",
                        "ETHUSDT 2000.00 1800.00 6000.00",
                        "BTCUSDT 50000.00 45000.00 150000.00");

        List<Object> before = snapshot();
        Run second = load();

        assertThat(second.exitCode()).as(second.output()).isZero();
        assertThat(second.output())
                .contains("BTCUSDT brackets: unchanged", "ETHUSDT pair: unchanged", "3 ACTIVE plans, unchanged");
        assertThat(snapshot())
                .as("a second load writes nothing, audit trail included")
                .isEqualTo(before);
    }

    @Test
    void BR62_aDemoPlanCancelledDuringTheDemo_isReplacedByTheNextLoad() {
        assertThat(load().exitCode()).isZero();
        UUID cancelled = (UUID) activePlansOfDemoFree().getFirst().get("plan_id");
        plans.cancel(DEMO_FREE, cancelled);
        assertThat(activePlansOfDemoFree()).hasSize(2);

        Run again = load();

        assertThat(again.exitCode()).as(again.output()).isZero();
        assertThat(again.output()).contains("plan 3 of 3 ACTIVE").doesNotContain("plan 1 of 3", "plan 2 of 3");
        assertThat(activePlansOfDemoFree()).hasSize(3).noneMatch(plan -> cancelled.equals(plan.get("plan_id")));
    }

    @Test
    void UC45_UC46_aPairAnAdministratorChanged_isPutBackByTheNextLoad() {
        assertThat(load().exitCode()).isZero();
        // The ceiling first: a first bracket below the pair's ceiling is refused.
        pairAdmin.update(ADMIN_ID, eth, new UpdatePairRequest(PairStatus.ACTIVE, true, false, 20, 2));
        pairAdmin.replaceBrackets(
                ADMIN_ID,
                eth,
                new LeverageBracketsRequest(List.of(new LeverageBracketRequest(
                        BigDecimal.ZERO, new BigDecimal("1000000"), 20, new BigDecimal("0.01"), BigDecimal.ZERO))));

        Run again = load();

        assertThat(again.exitCode()).as(again.output()).isZero();
        assertThat(again.output())
                .contains("ETHUSDT brackets: replaced (12)", "ETHUSDT pair: ACTIVE", "BTCUSDT pair: unchanged");
        assertPairsAsInTheDataFile();
    }

    @Test
    void DEMO01_withoutASpotPrice_theLoadStopsBeforeAnyPlan() {
        sql.sql("delete from spot_market_data").update();

        Run run = load(passwords(), List.of("-PriceWaitSeconds", "0"));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("No Spot price for BTCUSDT yet after 0 s");
        assertPairsAsInTheDataFile();
        assertThat(activePlansOfDemoFree()).isEmpty();
    }

    /** The staging case: the pairs are switched on by this very load, and their first price comes while it waits. */
    @Test
    void DEMO01_aPriceThatArrivesWhileTheLoaderWaits_isUsed() throws Exception {
        sql.sql("delete from spot_market_data").update();

        CompletableFuture<Run> running =
                CompletableFuture.supplyAsync(() -> load(passwords(), List.of("-PriceWaitSeconds", "90")));
        Instant deadline = Instant.now().plusSeconds(60);
        while (activePairs() < 2 && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(activePairs()).as("the loader switched both pairs on").isEqualTo(2);
        price(btc, "BTCUSDT", BTC_PRICE);
        price(eth, "ETHUSDT", ETH_PRICE);
        Run run = running.get(2, TimeUnit.MINUTES);

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(activePlansOfDemoFree()).hasSize(3);
    }

    /**
     * A plan the backend does not leave ACTIVE stops the load: here the cached last price the activation reads is
     * already under the entry the stored Spot price gave, so the LIMIT fills at once (BR-33).
     */
    @Test
    void BR33_aDemoPlanThatFillsAtActivation_stopsTheLoad() {
        cachedPrice("BTCUSDT", new BigDecimal("49000.00"), Instant.now().plusSeconds(1));

        Run run = load();

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("was saved as EXECUTED, not ACTIVE");
        assertThat(activePlansOfDemoFree()).isEmpty();
        assertThat(sql.sql("""
                        select count(*) from trading_plan t
                         where t.user_id = ? and t.plan_status = 'EXECUTED'
                           and exists (select 1 from trading_journal j where j.plan_id = t.plan_id)""").param(DEMO_FREE).query(Integer.class).single())
                .as("the plan the backend filled has its journal row, as every EXECUTED plan (D-118)")
                .isOne();
    }

    @Test
    void DEMO01_aPairTheSynchronisationHasNotCreated_stopsTheLoad() {
        new MarketTestData(sql, Instant.now()).remove(eth);

        Run run = load();

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("ETHUSDT is not a pair yet");
        assertThat(activePlansOfDemoFree()).isEmpty();
    }

    @Test
    void DEMO01_withoutThePasswords_nothingIsWritten() {
        Run run = load(Map.of(), List.of());

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("ADMIN_PASSWORD is not set");
        assertThat(activePairs()).isZero();
        assertThat(sql.sql("select count(*) from leverage_bracket")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void DEMO01_aDataFileWhosePlansNameAnotherPair_stopsTheLoad() throws IOException {
        ObjectNode changed = (ObjectNode) data.root().deepCopy();
        ((ObjectNode) changed.get("freePlans"))
                .putArray("symbols")
                .add("BTCUSDT")
                .add("SOLUSDT")
                .add("BTCUSDT");
        Path file = temporary.resolve("wf01.json");
        Files.writeString(file, JsonMapper.builder().build().writeValueAsString(changed));

        Run run = load(passwords(), List.of("-DataFile", file.toString()));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("freePlans names SOLUSDT");
        assertThat(activePlansOfDemoFree()).hasSize(1);
    }

    /**
     * DS-01c on the brackets the loader put in place: demo.pro (PRO, BALANCED, 10,000 at 1 %) plans a BTCUSDT Futures
     * LIMIT LONG at 50x with the stop loss 5 % under the entry. The risk of 100 over a stop of 4,950 is a notional of
     * about 2,000, in tier 1 (MMR 0.4 %), so the position is liquidated about 1.6 % under the entry, well before the
     * stop: BLOCKING SL_BEYOND_LIQUIDATION. The draft is saved with it, and Activate answers MSG18.
     */
    @Test
    void MSG18_DS01c_aStopLossBeyondLiquidation_onTheSeededBrackets_blocksTheActivation() throws Exception {
        assertThat(load().exitCode()).isZero();
        futuresLastPrice("BTCUSDT", BTC_PRICE);
        String token = accessToken("demo.pro@cryptopilot.invalid");

        HttpResponse<String> saved = call("POST", "/api/v1/plans", token, """
                {"pairId": "%s", "market": "FUTURES", "direction": "LONG", "entryType": "LIMIT",
                 "entryPrice": 99000, "stopLoss": 94050, "takeProfit": 110000, "leverage": 50}""".formatted(btc));

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        JsonNode plan = json(saved);
        assertThat(plan.get("status").asString()).isEqualTo("DRAFT");
        assertThat(plan.get("blocksActivation").asBoolean()).isTrue();
        List<String> warnings = new ArrayList<>();
        plan.get("warnings")
                .forEach(warning -> warnings.add(warning.get("type").asString() + " "
                        + warning.get("severity").asString()));
        assertThat(warnings).contains("SL_BEYOND_LIQUIDATION BLOCKING");
        BigDecimal liquidation = new BigDecimal(
                plan.get("snapshot").get("estimatedLiquidationPrice").asString());
        assertThat(liquidation)
                .as("tier 1 of the seeded brackets at 50x: liquidated about 1.6 % under the entry, above the stop")
                .isBetween(new BigDecimal("97000"), new BigDecimal("98000"));

        String id = plan.get("id").asString();
        HttpResponse<String> activation = call("POST", "/api/v1/plans/" + id + "/activate", token, null);

        assertThat(activation.statusCode()).as(activation.body()).isEqualTo(409);
        assertThat(json(activation).get("code").asString()).isEqualTo("TRADING_BLOCKING_WARNING");
        assertThat(json(activation).get("messageCode").asString()).isEqualTo("MSG18");
        assertThat(sql.sql("select plan_status from trading_plan where plan_id = ?")
                        .param(UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo("DRAFT");
    }

    private void assertPairsAsInTheDataFile() {
        for (String symbol : data.symbols()) {
            Map<String, Object> pair = sql.sql("""
                            select pair_id, pair_status, is_spot_enabled, is_futures_enabled, max_leverage
                              from crypto_pair where symbol = ?""").param(symbol).query().singleRow();
            assertThat(pair)
                    .as(symbol)
                    .containsEntry("pair_status", "ACTIVE")
                    .containsEntry("is_spot_enabled", true)
                    .containsEntry("is_futures_enabled", true)
                    .containsEntry("max_leverage", data.maxLeverage(symbol));

            List<Map<String, Object>> stored =
                    sql.sql("""
                            select notional_floor, notional_cap, max_leverage, maintenance_margin_rate, maintenance_amount
                              from leverage_bracket where pair_id = ? order by bracket_no""").param(pair.get("pair_id")).query().listOfRows();
            List<LeverageBracketRequest> wanted = data.brackets(symbol);
            assertThat(stored).as(symbol).hasSize(wanted.size());
            for (int index = 0; index < wanted.size(); index++) {
                Map<String, Object> row = stored.get(index);
                LeverageBracketRequest want = wanted.get(index);
                assertThat(decimal(row, "notional_floor")).isEqualByComparingTo(want.notionalFloor());
                assertThat(decimal(row, "notional_cap")).isEqualByComparingTo(want.notionalCap());
                assertThat(row.get("max_leverage")).isEqualTo(want.maxLeverage());
                assertThat(decimal(row, "maintenance_margin_rate")).isEqualByComparingTo(want.maintenanceMarginRate());
                assertThat(decimal(row, "maintenance_amount")).isEqualByComparingTo(want.maintenanceAmount());
            }
        }
    }

    private int activePairs() {
        return sql.sql("select count(*) from crypto_pair where pair_status = 'ACTIVE'")
                .query(Integer.class)
                .single();
    }

    private List<Map<String, Object>> activePlansOfDemoFree() {
        return sql.sql("""
                        select t.plan_id, p.symbol, t.market_type, t.direction, t.entry_type, t.entry_price,
                               t.stop_loss_price, t.take_profit_price, t.expires_at, t.plan_note
                          from trading_plan t join crypto_pair p on p.pair_id = t.pair_id
                         where t.user_id = ? and t.plan_status = 'ACTIVE'
                         order by t.created_at, t.plan_id""").param(DEMO_FREE).query().listOfRows();
    }

    /** Everything the loader could write, to compare one load with the next. */
    private List<Object> snapshot() {
        List<Object> rows = new ArrayList<>();
        rows.addAll(sql.sql("select * from crypto_pair order by symbol").query().listOfRows());
        rows.addAll(sql.sql("select * from leverage_bracket order by pair_id, bracket_no")
                .query()
                .listOfRows());
        rows.addAll(
                sql.sql("select * from trading_plan order by plan_id").query().listOfRows());
        rows.add(sql.sql("select count(*) from audit_log").query(Integer.class).single());
        return rows;
    }

    /** The Spot price as NSF-03 leaves it: the stored snapshot the market statistics read, and the cached last price. */
    private void price(UUID pair, String symbol, BigDecimal value) {
        Instant now = Instant.now();
        sql.sql("insert into spot_market_data (pair_id, snapshot_time, last_price) values (?, ?, ?)")
                .params(pair, Timestamp.from(now), value)
                .update();
        cachedPrice(symbol, value, now);
    }

    /** The cached Spot last price alone, the one an activation reads (D-112). */
    private void cachedPrice(String symbol, BigDecimal value, Instant at) {
        prices.record(
                MarketType.SPOT,
                new TickerMessage(
                        symbol,
                        value,
                        value,
                        value,
                        value,
                        value,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        at));
        prices.flush();
    }

    /** The Futures last price as NSF-03 leaves it: the close of the forming 1-minute candle (D-111). */
    private void futuresLastPrice(String symbol, BigDecimal value) {
        Instant at = Instant.now();
        Instant open = at.truncatedTo(ChronoUnit.MINUTES);
        prices.record(
                MarketType.FUTURES,
                new KlineMessage(
                        symbol,
                        MarketInterval.ONE_MINUTE,
                        new Kline(
                                open,
                                open.plusSeconds(60).minusMillis(1),
                                value,
                                value,
                                value,
                                value,
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                1),
                        false,
                        at));
        prices.flush();
    }

    private String accessToken(String email) throws Exception {
        HttpResponse<String> session = call("POST", "/api/v1/auth/login", null, """
                {"email": "%s", "password": "%s", "rememberMe": false}""".formatted(email, DEMO_PASSWORD));
        assertThat(session.statusCode()).as(session.body()).isEqualTo(200);
        return json(session).get("accessToken").asString();
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JsonMapper.builder().build().readTree(response.body());
    }

    private static BigDecimal decimal(Map<String, Object> row, String column) {
        return (BigDecimal) row.get(column);
    }

    /** A stored price on the 0.01 tick, written with two decimals. */
    private static BigDecimal cents(Map<String, Object> row, String column) {
        return decimal(row, column).setScale(2, RoundingMode.UNNECESSARY);
    }

    private Map<String, String> passwords() {
        return Map.of("ADMIN_PASSWORD", ADMIN_PASSWORD, "DEMO_PASSWORD", DEMO_PASSWORD);
    }

    private Run load() {
        return load(passwords(), List.of());
    }

    /** Runs the loader against this backend with only the given variables of its own, and waits for it. */
    private Run load(Map<String, String> environment, List<String> arguments) {
        List<String> command = new ArrayList<>(PowerShell.COMMAND);
        command.add(DemoWf01Data.SCRIPT.toAbsolutePath().toString());
        command.add("-BaseUrl");
        command.add("http://localhost:" + port);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        for (String name : List.of("ADMIN_PASSWORD", "DEMO_PASSWORD", "DEMO_ADMIN_EMAIL", "CRYPTOPILOT_BASE_URL")) {
            builder.environment().remove(name);
        }
        builder.environment().putAll(environment);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(2, TimeUnit.MINUTES))
                    .as("the loader finished")
                    .isTrue();
            return new Run(process.exitValue(), output);
        } catch (IOException failure) {
            throw new IllegalStateException("the loader could not be started: " + command, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the loader ran", interrupted);
        }
    }

    private record Run(int exitCode, String output) {}

    /** The PowerShell of this machine: {@code pwsh} where installed (the CI runner), Windows PowerShell otherwise. */
    private static final class PowerShell {

        static final List<String> COMMAND = find();

        private static List<String> find() {
            for (String shell : List.of("pwsh", "powershell")) {
                try {
                    Process probe = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                            .redirectErrorStream(true)
                            .start();
                    probe.getInputStream().readAllBytes();
                    if (probe.waitFor(1, TimeUnit.MINUTES) && probe.exitValue() == 0) {
                        return List.of(shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File");
                    }
                } catch (IOException notInstalled) {
                    // Try the next one.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new IllegalStateException(
                    "deploy/demo/load-wf01.ps1 needs PowerShell: install pwsh (PowerShell 7) or run on Windows");
        }
    }
}
