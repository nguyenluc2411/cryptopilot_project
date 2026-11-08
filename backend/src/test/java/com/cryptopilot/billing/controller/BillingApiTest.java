package com.cryptopilot.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.billing.client.GatewayUnavailableException;
import com.cryptopilot.billing.client.PaymentGateway;
import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Trader billing API end to end against the seeded packages, with a stand-in SePay gateway and no MoMo one.
 *
 * <p>Rule: BR-53…BR-56, BR-63 as amended by D-146; SRS 3.10; TECHNICAL_DESIGN 7.11.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, BillingApiTest.StandInGateway.class})
@Transactional
class BillingApiTest {

    private static final String BILLING = "/api/v1/billing";

    private static final Map<String, UUID> PACKAGES = Map.of(
            "PRO_MONTHLY", UUID.fromString("019b76da-a800-7002-8000-000000000002"),
            "PRO_YEARLY", UUID.fromString("019b76da-a800-7002-8000-000000000003"),
            "PREMIUM_MONTHLY", UUID.fromString("019b76da-a800-7002-8000-000000000004"));

    /** Whether the stand-in gateway fails its next checkout. */
    static final AtomicBoolean SEPAY_DOWN = new AtomicBoolean();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoSpyBean
    private SystemSettingApi settings;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void gatewayBackUp() {
        SEPAY_DOWN.set(false);
    }

    @Test
    void SCR30_thePackages_areFreeThenEveryPaidPackageOnSale() throws Exception {
        mvc.perform(get(BILLING + "/packages").header("Authorization", bearer(trader())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].code").value("FREE"))
                .andExpect(jsonPath("$[0].purchasable").value(false))
                .andExpect(jsonPath("$[1].tier").value("PRO"))
                .andExpect(jsonPath("$[?(@.code == 'PRO_MONTHLY')].price").value(hasItem("99000")))
                .andExpect(jsonPath("$[?(@.code == 'PREMIUM_YEARLY')].durationDays")
                        .value(hasItem(365)));
    }

    /** SePay has a configured implementation here and MoMo none, so only SePay is open. */
    @Test
    void D146_aGatewayIsOpen_onlyWhenSwitchedOnAndConfigured() throws Exception {
        mvc.perform(get(BILLING + "/gateways").header("Authorization", bearer(trader())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code == 'SEPAY')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.code == 'MOMO')].enabled").value(false));
    }

    @Test
    void D146_aGatewaySwitchedOff_isClosedAndRefusesNewOrders() throws Exception {
        doReturn(false).when(settings).flag("PAYMENT_SEPAY_ENABLED");
        String token = bearer(trader());

        mvc.perform(get(BILLING + "/gateways").header("Authorization", token))
                .andExpect(jsonPath("$[?(@.code == 'SEPAY')].enabled").value(false));
        mvc.perform(createOrder(token, "PRO_MONTHLY", "SEPAY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_DISABLED"));
    }

    @Test
    void D146_anOrderThroughMomoOrTheLegacyVnpay_isRefused() throws Exception {
        String token = bearer(trader());

        for (String gateway : new String[] {"MOMO", "VNPAY"}) {
            mvc.perform(createOrder(token, "PRO_MONTHLY", gateway))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_DISABLED"));
        }
    }

    @Test
    void D146_withoutASubscription_theQuoteIsANewPeriodFromNow() throws Exception {
        mvc.perform(quote(bearer(trader()), "PRO_YEARLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderType").value("NEW"))
                .andExpect(jsonPath("$.amount").value("990000"))
                .andExpect(jsonPath("$.currency").value("VND"))
                .andExpect(jsonPath("$.carriedValueVnd").value("0"))
                .andExpect(jsonPath("$.bonusDays").value(0));
    }

    /** PRO_MONTHLY with 20 of 30 days left is worth about 66.000, which buys 9 days of PREMIUM_MONTHLY. */
    @Test
    void D146_anUpgradeQuote_showsTheValueCarriedOverAsDays() throws Exception {
        UUID trader = trader();
        subscribe(trader, "PRO_MONTHLY", Instant.now().minus(Duration.ofDays(10)), Duration.ofDays(30));

        String body = mvc.perform(quote(bearer(trader), "PREMIUM_MONTHLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderType").value("UPGRADE"))
                .andExpect(jsonPath("$.amount").value("199000"))
                .andExpect(jsonPath("$.bonusDays").value(9))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 20 days left less the milliseconds between the insert and the request: 65.999 or 66.000.
        assertThat(new BigDecimal(JsonPath.<String>read(body, "$.carriedValueVnd")))
                .isBetween(new BigDecimal("65990"), new BigDecimal("66000"));
    }

    @Test
    void D146_theSameTier_isQuotedAsARenewal() throws Exception {
        UUID trader = trader();
        subscribe(trader, "PRO_MONTHLY", Instant.now().minus(Duration.ofDays(10)), Duration.ofDays(30));

        mvc.perform(quote(bearer(trader), "PRO_YEARLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderType").value("RENEW"))
                .andExpect(jsonPath("$.bonusDays").value(0));
    }

    @Test
    void D146_aLowerTier_isRefusedWithThePlanHeldAndItsEnd() throws Exception {
        UUID trader = trader();
        subscribe(trader, "PREMIUM_MONTHLY", Instant.now().minus(Duration.ofDays(1)), Duration.ofDays(30));
        String token = bearer(trader);

        mvc.perform(quote(token, "PRO_YEARLY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_DOWNGRADE_REFUSED"))
                .andExpect(jsonPath("$.messageArgs[0]").value("Premium Monthly"));
        mvc.perform(createOrder(token, "PRO_YEARLY", "SEPAY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_DOWNGRADE_REFUSED"));
    }

    @Test
    void SRS3115_freeIsNotSold_andAnUnknownPackageIsNotFound() throws Exception {
        String token = bearer(trader());

        mvc.perform(createOrder(token, "FREE", "SEPAY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_NOT_ON_SALE"));
        mvc.perform(quote(token, "GOLD")).andExpect(status().isNotFound());
    }

    @Test
    void MSG01_anOrderNamesAPackageAndAGateway() throws Exception {
        mvc.perform(post(BILLING + "/orders")
                        .header("Authorization", bearer(trader()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageCode\":\"PRO_MONTHLY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.gateway").exists());
    }

    /** BR-54, BR-56: a PENDING order at the package price, payable for ORDER_EXPIRY_MINUTES, with its checkout. */
    @Test
    void BR56_anOrder_isPendingAtThePackagePriceAndCarriesItsCheckout() throws Exception {
        UUID trader = trader();

        String body = mvc.perform(createOrder(bearer(trader), "PRO_MONTHLY", "SEPAY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.orderCode")
                        .value(org.hamcrest.Matchers.matchesPattern("CP\\d{12}[0-9A-Z]{4}")))
                .andExpect(jsonPath("$.order.status").value("PENDING"))
                .andExpect(jsonPath("$.order.orderType").value("NEW"))
                .andExpect(jsonPath("$.order.amount").value("99000"))
                .andExpect(jsonPath("$.order.gateway").value("SEPAY"))
                .andExpect(jsonPath("$.order.packageCode").value("PRO_MONTHLY"))
                .andExpect(jsonPath("$.checkout.method").value("POST"))
                .andExpect(jsonPath("$.checkout.url").value("https://pay.example.test/checkout"))
                .andExpect(jsonPath("$.checkout.fields.amount").value("99000"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String code = orderCode(body);
        entityManager.flush();

        assertThat(body).contains("payment-result?orderCode=" + code);
        Map<String, Object> row = sql.sql("""
                        select order_status, order_type, amount,
                               round(extract(epoch from expires_at - created_at)) as payable_seconds
                          from subscription_order where order_code = ?""").param(code).query().singleRow();
        assertThat(row.get("order_status")).isEqualTo("PENDING");
        assertThat(row.get("order_type")).isEqualTo("NEW");
        assertThat(row.get("amount").toString()).isEqualTo("99000");
        assertThat(((Number) row.get("payable_seconds")).longValue()).isEqualTo(15 * 60);
    }

    @Test
    void D146_aGatewayThatDoesNotAnswer_leavesNoOrder() throws Exception {
        UUID trader = trader();
        SEPAY_DOWN.set(true);

        mvc.perform(createOrder(bearer(trader), "PRO_MONTHLY", "SEPAY"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_UNAVAILABLE"));
        assertThat(sql.sql("select count(*) from subscription_order where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void SCR32_theCallerSeesTheirOwnOrders_newestFirst_andNoOneElses() throws Exception {
        UUID trader = trader();
        String token = bearer(trader);
        String first = orderCode(mvc.perform(createOrder(token, "PRO_MONTHLY", "SEPAY"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        String second = orderCode(mvc.perform(createOrder(token, "PREMIUM_MONTHLY", "SEPAY"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        sql.sql("update subscription_order set created_at = created_at - interval '1 minute' where order_code = ?")
                .param(first)
                .update();

        mvc.perform(get(BILLING + "/orders").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].orderCode").value(second))
                .andExpect(jsonPath("$.items[1].orderCode").value(first))
                .andExpect(jsonPath("$.items[1].packageName").value("Pro Monthly"));
        mvc.perform(get(BILLING + "/orders/" + first).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(BILLING + "/orders/" + first).header("Authorization", bearer(trader())))
                .andExpect(status().isNotFound());
    }

    @Test
    void SCR07_thePlanHeld_isFreeWithoutASubscriptionAndThePackageWithOne() throws Exception {
        UUID subscriber = trader();
        Instant start = Instant.now().minus(Duration.ofDays(1));
        subscribe(subscriber, "PRO_YEARLY", start, Duration.ofDays(365));

        mvc.perform(get(BILLING + "/subscription").header("Authorization", bearer(trader())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.packageCode").doesNotExist());
        mvc.perform(get(BILLING + "/subscription").header("Authorization", bearer(subscriber)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PRO"))
                .andExpect(jsonPath("$.packageCode").value("PRO_YEARLY"))
                .andExpect(jsonPath("$.bonusDays").value(0));
    }

    private org.springframework.test.web.servlet.RequestBuilder quote(String token, String packageCode) {
        return post(BILLING + "/orders/quote")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageCode\":\"" + packageCode + "\"}");
    }

    private org.springframework.test.web.servlet.RequestBuilder createOrder(
            String token, String packageCode, String gateway) {
        return post(BILLING + "/orders")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageCode\":\"" + packageCode + "\",\"gateway\":\"" + gateway + "\"}");
    }

    private static String orderCode(String body) {
        return JsonPath.read(body, "$.order.orderCode");
    }

    /** A PAID order for the package and the ACTIVE subscription it created, as SQL. */
    private void subscribe(UUID trader, String packageCode, Instant start, Duration period) {
        UUID order = UUID.randomUUID();
        Timestamp at = Timestamp.from(start);
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        select ?, ?, package_id, ?, price_amount, 'VND', 'SEPAY', 'PAID', ?, ?, ?, ?
                          from subscription_package where package_id = ?""")
                .params(
                        order,
                        trader,
                        "T066-" + order,
                        at,
                        at,
                        Timestamp.from(start.plusSeconds(900)),
                        at,
                        PACKAGES.get(packageCode))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(UUID.randomUUID(), order, at, Timestamp.from(start.plus(period)), at, at)
                .update();
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t066.invalid", now, now).update();
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

    /** A SePay that signs nothing and posts a form with the order's amount and return address. */
    @TestConfiguration
    static class StandInGateway {

        /** Ahead of the real SePay bean, which has no credentials in a test. */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        PaymentGateway standInSepay() {
            return new PaymentGateway() {
                @Override
                public GatewayCode code() {
                    return GatewayCode.SEPAY;
                }

                @Override
                public boolean configured() {
                    return true;
                }

                @Override
                public Checkout checkout(CheckoutRequest request) {
                    if (SEPAY_DOWN.get()) {
                        throw new GatewayUnavailableException("stand-in gateway is down");
                    }
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("order_invoice_number", request.orderCode());
                    fields.put("amount", request.amount().toPlainString());
                    fields.put("success_url", request.returnUrl().toString());
                    return Checkout.form(URI.create("https://pay.example.test/checkout"), fields);
                }

                @Override
                public GatewayLookup lookup(String orderCode) {
                    return GatewayLookup.of(GatewayLookup.State.AWAITING);
                }
            };
        }
    }
}
