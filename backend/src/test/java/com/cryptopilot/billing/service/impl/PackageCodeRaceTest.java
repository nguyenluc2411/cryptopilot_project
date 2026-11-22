package com.cryptopilot.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.dto.request.CreatePackageRequest;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.service.PackageAdminService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SCR-39: two administrators creating one code at once. The second passes the code check while the first has not
 * committed, then meets {@code uq_subscription_package_code} on its insert, and is told the code is taken (MSG for
 * {@code PACKAGE_CODE_TAKEN}) rather than a 500.
 *
 * <p>Rule: SRS 3.11.5; UC-42.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PackageCodeRaceTest {

    @Autowired
    private PackageAdminService packages;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient sql;

    private UUID admin;
    private String code;

    @BeforeEach
    void setUp() {
        admin = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'ADMIN', 'ACTIVE', ?, ?)""").params(admin, admin + "@race.invalid", at, at).update();
        code = "PRO_RACE_" + Long.toString(System.nanoTime() % 1_000_000L, 36).toUpperCase();
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from audit_log where user_id = ?").param(admin).update();
        sql.sql("delete from subscription_package where package_code = ?")
                .param(code)
                .update();
        sql.sql("delete from user_account where user_id = ?").param(admin).update();
    }

    @Test
    void UC42_aCodeTakenBetweenTheCheckAndTheInsert_isToldAsTaken() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            packages.create(admin, request());
            firstInserted.countDown();
            await(commitFirst);
        }));
        assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();

        // The first insert is not committed: the second sees no such code, and its insert waits on the unique index.
        CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(() -> {
            try {
                packages.create(admin, request());
                return null;
            } catch (RuntimeException refused) {
                return refused;
            }
        });
        Thread.sleep(300);
        commitFirst.countDown();
        first.get(10, TimeUnit.SECONDS);

        assertThat(second.get(10, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.PACKAGE_CODE_TAKEN));
        assertThat(sql.sql("select count(*) from subscription_package where package_code = ?")
                        .param(code)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private CreatePackageRequest request() {
        return new CreatePackageRequest(code, "Pro Race", PlanTier.PRO, 249_000L, 90, false);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
