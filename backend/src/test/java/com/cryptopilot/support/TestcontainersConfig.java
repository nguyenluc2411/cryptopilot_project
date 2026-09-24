package com.cryptopilot.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The database every integration test runs against: a real PostgreSQL with the TimescaleDB
 * extension, on the same image tag as the development compose file and staging. Testing the schema
 * against anything else — an in-memory database in particular — would only prove that the
 * migration is valid for a dialect nobody deploys.
 *
 * <p>The container is a static field, so the whole suite shares one database however many
 * application contexts the tests build; {@code @ServiceConnection} then points each context's
 * data source at it, which is why no test has to know a JDBC URL.
 *
 * <p>Rule: D-18 (database stack); TECHNICAL_DESIGN sections 6 and 11.1.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    /**
     * Kept equal to the tag in {@code deploy/docker-compose.dev.yml} and on staging (D-18). The
     * image is declared a substitute for {@code postgres} because it is PostgreSQL with an
     * extension, which Testcontainers cannot infer from the name alone.
     */
    private static final DockerImageName TIMESCALE_IMAGE =
            DockerImageName.parse("timescale/timescaledb:2.30.1-pg16").asCompatibleSubstituteFor("postgres");

    /**
     * Connections the test database accepts, set explicitly rather than left to the image.
     *
     * <p>The TimescaleDB image tunes {@code max_connections} to the machine it starts on, so the limit
     * differs between a developer's laptop and a CI runner. The suite keeps one Spring context per
     * distinct test configuration alive at a time, each with its own connection pool of ten, and ten
     * such contexts reached the runner's limit ("too many clients already") while passing locally. A
     * fixed limit makes the suite behave the same everywhere; it is a test-only setting and changes
     * nothing about the application or any other environment.
     */
    private static final int MAX_CONNECTIONS = 300;

    private static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("cryptopilot")
            .withUsername("cryptopilot")
            .withPassword("cryptopilot")
            .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS);

    @Bean
    @ServiceConnection
    PostgreSQLContainer timescaleContainer() {
        return TIMESCALE;
    }
}
