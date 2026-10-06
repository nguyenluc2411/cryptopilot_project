package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.paper.model.OpenedAccount;
import com.cryptopilot.paper.service.PaperAccountService;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The paper account API end to end: opening with the virtual funds, granted once (Spot 10 000 USDT, 0.1 BTC, 1 ETH,
 * 10 BNB; Futures 5 000 USDT), the wallets, transfers and the transaction history; reads that never open an account.
 *
 * <p>Nothing is rolled back, because the concurrency test needs committed transactions: every test writes its own
 * Traders and {@link #removeRows()} deletes them with their paper rows. The test database stores no coin, so opening
 * an account is also what stores the coins of the grant; those are removed again afterwards.
 *
 * <p>Rule: TR-04; Q-T5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PaperAccountApiTest {

    private static final String PAPER = "/api/v1/paper";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PaperAccountService service;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();

    @BeforeEach
    void noteTheCoinsAlreadyStored() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
    }

    @AfterEach
    void removeRows() {
        for (UUID trader : traders) {
            String account = "(select account_id from paper_account where user_id = ?)";
            for (String table : List.of("paper_ledger_entry", "paper_transfer", "paper_balance")) {
                sql.sql("delete from " + table + " where account_id = " + account)
                        .param(trader)
                        .update();
            }
            sql.sql("delete from paper_account where user_id = ?").param(trader).update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        // The seed and schema tests expect no coin they did not write, so the coins an opening stored go again.
        for (String symbol : List.of("USDT", "BTC", "ETH", "BNB")) {
            if (!preStoredCoins.contains(symbol)) {
                sql.sql("delete from coin where symbol = ?").param(symbol).update();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------------------------------

    @Test
    void QT5_opening_grantsTheVirtualFundsOnce() throws Exception {
        UUID trader = trader();

        String id = JsonPath.read(
                as(trader, post(PAPER + "/account"), null)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.positionMode").value("ONE_WAY"))
                        .andExpect(jsonPath("$.openedAt").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.accountId");

        assertThat(holdings(trader, "SPOT"))
                .containsOnlyKeys("USDT", "BTC", "ETH", "BNB")
                .hasEntrySatisfying("USDT", amount -> assertThat(amount).isEqualByComparingTo("10000"))
                .hasEntrySatisfying("BTC", amount -> assertThat(amount).isEqualByComparingTo("0.1"))
                .hasEntrySatisfying("ETH", amount -> assertThat(amount).isEqualByComparingTo("1"))
                .hasEntrySatisfying("BNB", amount -> assertThat(amount).isEqualByComparingTo("10"));
        assertThat(holdings(trader, "FUTURES"))
                .containsOnlyKeys("USDT")
                .hasEntrySatisfying("USDT", amount -> assertThat(amount).isEqualByComparingTo("5000"));
        assertLedgerAddsUpToTheBalances(trader);

        as(trader, post(PAPER + "/account"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(id));
        assertThat(ledgerCount(trader, "INITIAL_GRANT"))
                .as("opening again grants nothing")
                .isEqualTo(5);
    }

    @Test
    void QT5_theGrant_isWrittenInTheOrderItIsConfigured() throws Exception {
        UUID trader = opened();

        // Newest first: the Futures USDT was granted last, the Spot USDT first.
        as(trader, get(PAPER + "/ledger"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].asset").value(contains("USDT", "BNB", "ETH", "BTC", "USDT")))
                .andExpect(jsonPath("$.items[0].wallet").value("FUTURES"))
                .andExpect(jsonPath("$.items[4].wallet").value("SPOT"));
    }

    @Test
    void anAccountOpenedBeforeTheSymbolSynchronisation_isStillGrantedEverything() throws Exception {
        assumeThat(preStoredCoins).as("the coins are not stored yet").isEmpty();

        UUID trader = opened();

        assertThat(holdings(trader, "SPOT")).containsOnlyKeys("USDT", "BTC", "ETH", "BNB");
        assertThat(holdings(trader, "FUTURES")).containsOnlyKeys("USDT");
        assertThat(sql.sql("select count(*) from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                        .query(Long.class)
                        .single())
                .isEqualTo(4);
    }

    /** A GET never opens an account: a crawler or a prefetch in front of it causes nothing. */
    @Test
    void beforeOpening_everyReadAnswersNotFound_andNothingIsWritten() throws Exception {
        UUID trader = trader();

        for (String path : List.of("/account", "/wallets/SPOT", "/transfers", "/ledger")) {
            as(trader, get(PAPER + path), null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("MSG41"));
        }
        as(trader, post(PAPER + "/transfers"), transfer("USDT", "SPOT", "1")).andExpect(status().isNotFound());

        assertThat(sql.sql("select count(*) from paper_account where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theAccount_isOpenedOnce_howeverManyCallsRace() throws Exception {
        UUID trader = trader();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OpenedAccount>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return service.open(trader);
                }));
            }
            start.countDown();
            List<OpenedAccount> results = new ArrayList<>();
            for (Future<OpenedAccount> result : calls) {
                results.add(result.get(30, TimeUnit.SECONDS));
            }
            UUID first = results.getFirst().account().accountId();
            assertThat(results)
                    .extracting(opened -> opened.account().accountId())
                    .containsOnly(first);
            assertThat(results).filteredOn(OpenedAccount::created).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledgerCount(trader, "INITIAL_GRANT"))
                .as("the funds are granted once")
                .isEqualTo(5);
    }

    @Test
    void eachTrader_seesOnlyTheirOwnAccount() throws Exception {
        String first = accountId(opened());
        String second = accountId(opened());

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void QT5_thereIsNoReset() throws Exception {
        as(opened(), post(PAPER + "/account/reset"), null).andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------------------------------
    // Wallets
    // ------------------------------------------------------------------------------------------

    @Test
    void aWallet_valuesWhatItCan_andSaysWhenItCouldNotValueEverything() throws Exception {
        UUID trader = opened();

        as(trader, get(PAPER + "/wallets/SPOT"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallet").value("SPOT"))
                .andExpect(jsonPath("$.balances.length()").value(4))
                .andExpect(jsonPath("$.balances[0].asset").value("USDT"))
                .andExpect(jsonPath("$.balances[0].usdtValue").value("10000.00000000"))
                .andExpect(jsonPath("$.estimatedUsdt").value("10000.00000000"))
                // No price is cached in this test, so the coins other than USDT are not valued.
                .andExpect(jsonPath("$.complete").value(false));
    }

    @Test
    void anUnknownWallet_isAValidationError() throws Exception {
        as(opened(), get(PAPER + "/wallets/MARGIN"), null).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------
    // Transfers
    // ------------------------------------------------------------------------------------------

    @Test
    void aTransfer_movesTheAmount_andRecordsBothSidesInTheLedger() throws Exception {
        UUID trader = opened();

        as(trader, post(PAPER + "/transfers"), transfer("usdt", "SPOT", "1000.5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.asset").value("USDT"))
                .andExpect(jsonPath("$.from").value("SPOT"))
                .andExpect(jsonPath("$.to").value("FUTURES"))
                .andExpect(jsonPath("$.amount").value("1000.50000000"));

        assertThat(holdings(trader, "SPOT").get("USDT")).isEqualByComparingTo("8999.5");
        assertThat(holdings(trader, "FUTURES").get("USDT")).isEqualByComparingTo("6000.5");
        assertThat(ledgerCount(trader, "TRANSFER")).isEqualTo(2);
        assertLedgerAddsUpToTheBalances(trader);
        as(trader, get(PAPER + "/transfers"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].amount").value("1000.50000000"));
    }

    @Test
    void aTransferOfMoreThanIsFree_isRefused_andChangesNothing() throws Exception {
        UUID trader = opened();

        as(trader, post(PAPER + "/transfers"), transfer("USDT", "FUTURES", "5000.00000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.messageArgs[0]").value("USDT"));

        assertThat(holdings(trader, "FUTURES").get("USDT")).isEqualByComparingTo("5000");
        assertThat(ledgerCount(trader, "TRANSFER")).isZero();
    }

    @Test
    void onlyUsdt_movesBetweenTheWallets_eitherWay() throws Exception {
        UUID trader = opened();

        for (String from : List.of("SPOT", "FUTURES")) {
            as(trader, post(PAPER + "/transfers"), transfer("BTC", from, "0.01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.asset").value("MSG15"));
        }

        assertThat(holdings(trader, "FUTURES"))
                .as("a refused transfer leaves no empty balance behind")
                .containsOnlyKeys("USDT");
    }

    @Test
    void aTraderWithoutAnAccount_isToldSo_beforeTheRequestIsJudged() throws Exception {
        as(trader(), post(PAPER + "/transfers"), transfer("BTC", "FUTURES", "0.01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
    }

    @Test
    void aTransferSentAgainWithItsKey_isMadeOnce() throws Exception {
        UUID trader = opened();
        String body = transfer("USDT", "SPOT", "1000", "web-7f3a");

        String first = JsonPath.read(
                as(trader, post(PAPER + "/transfers"), body)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.clientTransferId").value("web-7f3a"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.transferId");
        as(trader, post(PAPER + "/transfers"), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(first));

        assertThat(holdings(trader, "SPOT").get("USDT")).isEqualByComparingTo("9000");
        assertThat(holdings(trader, "FUTURES").get("USDT")).isEqualByComparingTo("6000");
        assertThat(ledgerCount(trader, "TRANSFER")).isEqualTo(2);
    }

    @Test
    void aKeyReusedForAnotherTransfer_isRefused() throws Exception {
        UUID trader = opened();
        as(trader, post(PAPER + "/transfers"), transfer("USDT", "SPOT", "1000", "web-1"))
                .andExpect(status().isCreated());

        as(trader, post(PAPER + "/transfers"), transfer("USDT", "SPOT", "999", "web-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        assertThat(holdings(trader, "SPOT").get("USDT")).isEqualByComparingTo("9000");
    }

    @Test
    void aTransferWithoutAKey_isGivenOne() throws Exception {
        as(opened(), post(PAPER + "/transfers"), transfer("USDT", "SPOT", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientTransferId").isNotEmpty());
    }

    @Test
    void anAmountWithMoreThanEightDecimals_isRefused() throws Exception {
        as(opened(), post(PAPER + "/transfers"), transfer("USDT", "SPOT", "1.000000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").value("MSG15"));
    }

    // ------------------------------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------------------------------

    @Test
    void theLedger_pages_newestFirst_andFilters() throws Exception {
        UUID trader = opened();

        as(trader, get(PAPER + "/ledger").param("pageSize", "2"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
        as(trader, get(PAPER + "/ledger").param("pageSize", "101"), null).andExpect(status().isBadRequest());
        as(trader, get(PAPER + "/ledger").param("type", "INITIAL_GRANT").param("wallet", "FUTURES"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void aLedgerPeriodThatEndsBeforeItStarts_isRefused() throws Exception {
        as(
                        opened(),
                        get(PAPER + "/ledger")
                                .param("from", "2026-10-07T00:00:00Z")
                                .param("to", "2026-10-07T00:00:00Z"),
                        null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.to").value("MSG15"));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Every balance's total equals the sum of its ledger entries. */
    private void assertLedgerAddsUpToTheBalances(UUID trader) {
        List<Map<String, Object>> drift = sql.sql("""
                        select b.wallet_type, b.coin_id, b.free_amount + b.locked_amount as total,
                               coalesce(sum(e.amount), 0) as entries
                          from paper_balance b
                          join paper_account a on a.account_id = b.account_id
                          left join paper_ledger_entry e
                            on e.account_id = b.account_id and e.wallet_type = b.wallet_type
                           and e.coin_id = b.coin_id
                         where a.user_id = ?
                         group by b.wallet_type, b.coin_id, b.free_amount, b.locked_amount
                        having b.free_amount + b.locked_amount <> coalesce(sum(e.amount), 0)""").param(trader).query().listOfRows();
        assertThat(drift).as("balances whose ledger does not add up to them").isEmpty();
    }

    private Map<String, BigDecimal> holdings(UUID trader, String wallet) {
        Map<String, BigDecimal> held = new HashMap<>();
        sql.sql("""
                        select c.symbol, b.free_amount + b.locked_amount as total
                          from paper_balance b
                          join paper_account a on a.account_id = b.account_id
                          join coin c on c.coin_id = b.coin_id
                         where a.user_id = ? and b.wallet_type = ?""")
                .params(trader, wallet)
                .query()
                .listOfRows()
                .forEach(row -> held.put((String) row.get("symbol"), (BigDecimal) row.get("total")));
        return held;
    }

    private long ledgerCount(UUID trader, String type) {
        return sql.sql("""
                        select count(*) from paper_ledger_entry e
                          join paper_account a on a.account_id = e.account_id
                         where a.user_id = ? and e.entry_type = ?""").params(trader, type).query(Long.class).single();
    }

    private String accountId(UUID trader) throws Exception {
        return JsonPath.read(
                as(trader, get(PAPER + "/account"), null)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.accountId");
    }

    private static String transfer(String asset, String from, String amount) {
        return """
                {"asset": "%s", "from": "%s", "amount": %s}""".formatted(asset, from, amount);
    }

    private static String transfer(String asset, String from, String amount, String clientTransferId) {
        return """
                {"asset": "%s", "from": "%s", "amount": %s, "clientTransferId": "%s"}""".formatted(asset, from, amount, clientTransferId);
    }

    private ResultActions as(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    /** A Trader whose paper account is open. */
    private UUID opened() throws Exception {
        UUID trader = trader();
        as(trader, post(PAPER + "/account"), null).andExpect(status().isCreated());
        return trader;
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@tr04.invalid", now(), now()).update();
        traders.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
