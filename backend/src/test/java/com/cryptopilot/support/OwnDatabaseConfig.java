package com.cryptopilot.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Points a context at a database of its own on the shared container, named by the property {@value #PROPERTY}, which
 * the context's Flyway migrates through the context's own locations. A context on the {@code demo} profile needs it:
 * the demo set must never reach the database the rest of the suite shares. The database is dropped and created again
 * when the context starts. Both the application and Flyway are pointed at it, since the shared container offers each
 * of them its own connection to the shared database. Import it after {@link TestcontainersConfig}.
 *
 * <p>Rule: DEMO-01; D-120.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OwnDatabaseConfig {

    /** The property that names the database. */
    public static final String PROPERTY = "cryptopilot.test.own-database";

    @Bean
    OwnDatabase ownDatabase(PostgreSQLContainer container, @Value("${" + PROPERTY + "}") String name)
            throws SQLException {
        container.start();
        try (Connection connection = DriverManager.getConnection(
                        container.getJdbcUrl(), container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + name);
        }
        String url = container.getJdbcUrl();
        return new OwnDatabase(
                url.substring(0, url.lastIndexOf('/') + 1) + name, container.getUsername(), container.getPassword());
    }

    @Bean
    @Primary
    JdbcConnectionDetails ownJdbc(OwnDatabase database) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return database.username();
            }

            @Override
            public String getPassword() {
                return database.password();
            }

            @Override
            public String getJdbcUrl() {
                return database.url();
            }
        };
    }

    @Bean
    @Primary
    FlywayConnectionDetails ownFlyway(OwnDatabase database) {
        return new FlywayConnectionDetails() {
            @Override
            public String getUsername() {
                return database.username();
            }

            @Override
            public String getPassword() {
                return database.password();
            }

            @Override
            public String getJdbcUrl() {
                return database.url();
            }
        };
    }

    /** Where the context's database is. */
    record OwnDatabase(String url, String username, String password) {}
}
