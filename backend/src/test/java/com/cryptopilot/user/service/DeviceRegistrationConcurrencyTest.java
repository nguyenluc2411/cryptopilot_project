package com.cryptopilot.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.user.dto.RegisterDeviceRequest;
import com.cryptopilot.user.entity.DevicePlatform;
import java.util.ArrayList;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two registrations of the same messaging token at the same moment, by two accounts (D-34).
 *
 * <p>Read-then-write would let both see no row and both insert — one refused by the unique index — or
 * both see the previous owner's row and both delete it — one failing on a row already gone. The upsert
 * lets the index serialise them. What must hold whatever the interleaving: neither registration fails,
 * exactly one row holds the token, it is active, and it belongs to one of the two accounts.
 *
 * <p>Each round releases both registrations from one latch so that they genuinely overlap, and the
 * rounds are repeated because a race that is asserted once is a race that was lucky once.
 *
 * <p>Rule: SRS 3.2.5; D-34.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DeviceRegistrationConcurrencyTest {

    private static final String TEST_DOMAIN = "@t014race.invalid";

    private static final int ROUNDS = 20;

    @Autowired
    private DeviceService devices;

    @Autowired
    private UserApi users;

    @Autowired
    private JdbcClient sql;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private UUID first;

    private UUID second;

    @BeforeEach
    void twoAccounts() {
        first = users.registerTrader("first" + TEST_DOMAIN, "hash", "First").userId();
        second = users.registerTrader("second" + TEST_DOMAIN, "hash", "Second").userId();
    }

    @AfterEach
    void removeWhatTheTestWrote() {
        pool.shutdownNow();
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
    }

    /** A token nobody holds, registered by two accounts at once: one owner, one active row, no failure. */
    @Test
    void D34_aNewTokenRegisteredByTwoAccountsAtOnce_endsWithExactlyOneActiveOwner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String token = "race-new-" + round;

            List<Future<?>> both = registerTogether(token);
            for (Future<?> registration : both) {
                registration.get(10, TimeUnit.SECONDS);
            }

            assertExactlyOneActiveOwner(token);
        }
    }

    /**
     * A token a third account already holds, taken over by two accounts at once — the case where
     * read-then-write deleted the same row twice. Still one owner, one row, no failure, and the previous
     * owner keeps nothing.
     */
    @Test
    void D34_aHeldTokenTakenOverByTwoAccountsAtOnce_endsWithExactlyOneActiveOwner() throws Exception {
        UUID previous = users.registerTrader("previous" + TEST_DOMAIN, "hash", "Previous")
                .userId();
        for (int round = 0; round < ROUNDS; round++) {
            String token = "race-held-" + round;
            devices.register(previous, new RegisterDeviceRequest(token, DevicePlatform.ANDROID));

            List<Future<?>> both = registerTogether(token);
            for (Future<?> registration : both) {
                registration.get(10, TimeUnit.SECONDS);
            }

            assertExactlyOneActiveOwner(token);
        }
    }

    private List<Future<?>> registerTogether(String token) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (UUID account : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                start.await();
                return devices.register(account, new RegisterDeviceRequest(token, DevicePlatform.IOS));
            }));
        }
        start.countDown();
        return futures;
    }

    private void assertExactlyOneActiveOwner(String token) {
        List<Map<String, Object>> rows = sql.sql("select user_id, is_active from user_device where fcm_token = ?")
                .param(token)
                .query()
                .listOfRows();
        assertThat(rows).as("exactly one row holds %s", token).hasSize(1);
        assertThat(rows.get(0).get("is_active")).isEqualTo(true);
        assertThat(rows.get(0).get("user_id")).isIn(first, second);
    }
}
