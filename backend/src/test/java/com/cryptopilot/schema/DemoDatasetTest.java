package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.util.FileCopyUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The development-only dataset, applied the way a developer's database applies it: the production
 * migrations first, then the demo location on top of them.
 *
 * <p>It runs in a database of its own on the same container. Applying it to the database the rest
 * of the suite shares would put demo rows in front of every other test, and what those tests exist
 * to assert is precisely that no demo row is there — so the one test that needs the demo set has to
 * keep it to itself. That is also what a real environment looks like: the demo location is a
 * property of one database, not of the image.
 *
 * <p>Rule: SRS 3.1.5; D-18 (the same database image in every environment).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DemoDatasetTest {

    private static final String DEMO_DATABASE = "cryptopilot_demo";

    /** The fixed key of the demo trader. Only the demo location writes it. */
    private static final String DEMO_TRADER_ID = "019b76da-a800-7d00-8000-000000000001";

    @Autowired
    private PostgreSQLContainer container;

    @Autowired
    private Flyway configuredFlyway;

    private SingleConnectionDataSource demoDataSource;
    private JdbcClient demoJdbc;

    /**
     * A database of its own, migrated through both locations. It is dropped and recreated for each
     * test, so a test never sees what another one left behind.
     */
    @BeforeEach
    void migrateDemoDatabase() throws SQLException {
        String adminUrl = container.getJdbcUrl();
        try (Connection connection =
                        DriverManager.getConnection(adminUrl, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DEMO_DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DEMO_DATABASE);
        }

        String demoUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DEMO_DATABASE;
        Flyway.configure()
                .dataSource(demoUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .placeholders(configuredFlyway.getConfiguration().getPlaceholders())
                .load()
                .migrate();

        demoDataSource =
                new SingleConnectionDataSource(demoUrl, container.getUsername(), container.getPassword(), true);
        demoJdbc = JdbcClient.create(demoDataSource);
    }

    @AfterEach
    void releaseDemoDatabase() {
        if (demoDataSource != null) {
            demoDataSource.destroy();
            demoDataSource = null;
            demoJdbc = null;
        }
    }

    @Test
    void theDemoSet_appliesOnTopOfTheProductionPathAndAddsOnlyItsOwnRows() {
        assertThat(count("user_account"))
                .as("the bootstrap administrator of the production path, and the demo trader beside it")
                .isEqualTo(2);
        assertThat(demoJdbc.sql("select email, role from user_account where user_id = ?::uuid")
                        .param(DEMO_TRADER_ID)
                        .query()
                        .singleRow())
                .containsEntry("email", "demo.trader@cryptopilot.invalid")
                .containsEntry("role", "TRADER");

        assertThat(count("user_profile")).isEqualTo(2);
        assertThat(count("system_setting"))
                .as("the demo set adds no setting: the reference values are the same in every environment")
                .isEqualTo(16);
        assertThat(count("trading_strategy")).isEqualTo(3);
        assertThat(count("crypto_pair"))
                .as("the pair list is an open decision, and a development-only file is not a way around it")
                .isZero();
        assertThat(count("subscription_package")).isZero();
    }

    @Test
    void reapplyingTheDemoSet_changesNothing() throws Exception {
        List<Map<String, Object>> before = accountRows();

        applyDemoScript();

        assertThat(accountRows()).isEqualTo(before);
        assertThat(count("user_account")).isEqualTo(2);
        assertThat(count("user_profile")).isEqualTo(2);
    }

    private List<Map<String, Object>> accountRows() {
        List<Map<String, Object>> rows = new ArrayList<>(demoJdbc.sql("select * from user_account order by email")
                .query()
                .listOfRows());
        rows.addAll(demoJdbc.sql("select * from user_profile order by display_name")
                .query()
                .listOfRows());
        return rows;
    }

    /** The demo file itself, with its placeholders resolved the way Flyway resolved them. */
    private void applyDemoScript() throws Exception {
        String sql = new String(
                FileCopyUtils.copyToByteArray(new ClassPathResource("db/demo/R__demo_dataset.sql").getInputStream()),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> placeholder :
                configuredFlyway.getConfiguration().getPlaceholders().entrySet()) {
            sql = sql.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        try (Connection connection = demoDataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), "demo dataset"),
                            StandardCharsets.UTF_8));
        }
    }

    private int count(String table) {
        return demoJdbc.sql("select count(*) from " + table)
                .query(Integer.class)
                .single();
    }
}
