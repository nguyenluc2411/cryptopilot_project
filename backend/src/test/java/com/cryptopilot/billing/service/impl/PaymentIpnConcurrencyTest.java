package com.cryptopilot.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.service.PaymentSettlementService;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two copies of the same IPN arriving at once, each in its own committed transaction: the order row lock makes them
 * run one after the other, so exactly one pays the order and opens one subscription (BR-53).
 *
 * <p>Rule: BR-53; NSF-12; TECHNICAL_DESIGN 5.5.
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11 (exactly-once
 * effect through idempotent processing).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PaymentIpnConcurrencyTest {

    private static final UUID PRO_MONTHLY = UUID.fromString("019b76da-a800-7002-8000-000000000002");

    @Autowired
    private PaymentSettlementService settlement;

    @Autowired
    private JdbcClient sql;

    private final List<UUID> traders = new ArrayList<>();

    @AfterEach
    void removeRows() throws InterruptedException {
        for (UUID trader : traders) {
            // The in-app notice of the payment is written after the commit; wait for it before removing the account.
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (Instant.now().isBefore(deadline) && notices(trader) == 0) {
                Thread.sleep(50);
            }
            sql.sql("delete from notification where user_id = ?").param(trader).update();
            sql.sql("delete from user_subscription where user_id = ?")
                    .param(trader)
                    .update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(trader)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
    }

    @Test
    void BR53_twoCopiesOfTheSameIpnAtOnce_payTheOrderOnce_andOpenOneSubscription() throws Exception {
        UUID trader = trader();
        String order = pendingOrder(trader);
        PaymentResult paid = new PaymentResult(
                GatewayCode.SEPAY, order, true, new BigDecimal("99000"), "TX-" + order, "CAPTURED", "{}");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    settlement.settle(paid);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sql.sql("select order_status from subscription_order where order_code = ?")
                        .param(order)
                        .query(String.class)
                        .single())
                .isEqualTo("PAID");
        assertThat(sql.sql("select subscription_status from user_subscription where user_id = ?")
                        .param(trader)
                        .query(String.class)
                        .list())
                .containsExactly("ACTIVE");
    }

    private long notices(UUID trader) {
        return sql.sql("select count(*) from notification where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private String pendingOrder(UUID trader) {
        String code = "CP"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 99000, 'VND', 'SEPAY', 'PENDING', ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        trader,
                        PRO_MONTHLY,
                        code,
                        now,
                        Timestamp.from(Instant.now().plusSeconds(900)),
                        now)
                .update();
        return code;
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@ipn-race.invalid", now, now).update();
        traders.add(id);
        return id;
    }
}
