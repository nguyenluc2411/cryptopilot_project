package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Proves that every enumeration is enforced by the database and not only by the mapping: for each
 * varchar column that carries a value list, one row with a value outside that list is offered to
 * PostgreSQL and has to be refused.
 *
 * <p>The list of columns is read from {@code pg_constraint}, so a check added by a later migration
 * is exercised here without anyone editing this test, and a check deleted by mistake stops being
 * exercised — which the sibling test on the reviewed constraint set catches.
 *
 * <p>Each row is offered to a temporary clone of the table, created with {@code LIKE … INCLUDING
 * ALL}. A clone copies the check constraints but not the foreign keys, so proving that
 * {@code post_report.reason} rejects a value does not require a post, a reporter and an account
 * first. Everything runs on one connection inside a transaction that is never committed, and each
 * attempt is wrapped in a savepoint because a failed statement makes a PostgreSQL transaction
 * unusable until it is rolled back.
 *
 * <p>The failure has to name a check constraint that mentions the column under test. Without that,
 * a row rejected for an unrelated reason — a missing sibling column, a violated range — would look
 * like a passing test.
 *
 * <p>Rule: SRS 3.1.5; TECHNICAL_DESIGN section 6 (enumerations are varchar plus a check).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class EnumCheckConstraintTest {

    private static final String OUTSIDE_EVERY_LIST = "ZZ_NOT_A_MEMBER";

    /** A quoted literal of a constraint definition, which is where the allowed values are. */
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

    /**
     * Values that keep the rest of a row legal while one column is made illegal. They exist only
     * for the tables whose constraints span more than one column: a plan that waits at a limit
     * price needs that price, a journal record is either simulated with a plan or manual without
     * one, an engagement points at exactly one target, a subscription ends after it starts, a feed
     * is polled at most every fifteen minutes (BR-49), and an indicator alert names an indicator.
     * A new cross-column rule that this map does not know about does not pass silently: the row is
     * refused by the wrong constraint and the test says so.
     */
    private static final Map<String, Map<String, Object>> VALID_COMPANIONS = Map.of(
            "trading_plan", Map.of("entry_type", "MARKET"),
            "trading_journal", Map.of("source", "MANUAL"),
            "engagement", Map.of("post_id", UUID.randomUUID()),
            "user_subscription", Map.of("end_at", java.sql.Timestamp.valueOf("2027-01-01 00:00:00")),
            "news_source", Map.of("crawl_interval_minutes", 15),
            "alert.timeframe", Map.of("alert_type", "INDICATOR", "indicator_name", "RSI"));

    @Autowired
    private DataSource dataSource;

    @Test
    void everyEnumeration_isRefusedAValueOutsideItsList() throws SQLException {
        List<String> complaints = new ArrayList<>();
        int covered = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<EnumColumn> enumColumns = readEnumColumns(connection);
                assertThat(enumColumns)
                        .as("the schema defines enumerations to exercise")
                        .hasSizeGreaterThan(30);

                Map<String, Set<String>> checksByColumn = readChecksByColumn(connection);
                Map<String, String> clones = new LinkedHashMap<>();
                for (EnumColumn column : enumColumns) {
                    clones.computeIfAbsent(column.table(), table -> cloneOf(connection, table));
                }

                for (EnumColumn column : enumColumns) {
                    covered++;
                    String complaint =
                            probe(connection, clones.get(column.table()), column, enumColumns, checksByColumn);
                    if (complaint != null) {
                        complaints.add(complaint);
                    }
                }
            } finally {
                connection.rollback();
            }
        }

        assertThat(complaints).as("enumerations the database does not enforce").isEmpty();
        assertThat(covered)
                .as("every varchar column that carries a value list was offered an invalid value")
                .isGreaterThan(30);
    }

    private String probe(
            Connection connection,
            String clone,
            EnumColumn target,
            List<EnumColumn> enumColumns,
            Map<String, Set<String>> checksByColumn) {
        Map<String, Object> row = rowFor(connection, target.table(), enumColumns, target);
        row.put(target.column(), OUTSIDE_EVERY_LIST);

        List<String> names = new ArrayList<>(row.keySet());
        String columns = String.join(", ", names);
        String placeholders = String.join(", ", names.stream().map(n -> "?").toList());
        String where = target.table() + "." + target.column();

        Savepoint savepoint = null;
        try {
            savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into " + clone + " (" + columns + ") values (" + placeholders + ")")) {
                for (int i = 0; i < names.size(); i++) {
                    statement.setObject(i + 1, row.get(names.get(i)));
                }
                statement.executeUpdate();
            }
            return where + " accepted '" + OUTSIDE_EVERY_LIST + "'";
        } catch (SQLException refused) {
            if (!"23514".equals(refused.getSQLState())) {
                return where + " was refused by " + refused.getSQLState() + " instead of a check violation: "
                        + refused.getMessage();
            }
            Set<String> mentioning = checksByColumn.getOrDefault(where, Set.of());
            boolean named = mentioning.stream().anyMatch(refused.getMessage()::contains);
            return named ? null : where + " was refused by a check that does not mention it: " + refused.getMessage();
        } finally {
            rollbackTo(connection, savepoint);
        }
    }

    /**
     * The smallest legal row of a table: every column the table insists on, filled with a value of
     * the right shape. Columns with a default are left out so the default applies, and nullable
     * columns are left out so a conditional constraint that forbids them stays satisfied.
     */
    private Map<String, Object> rowFor(
            Connection connection, String table, List<EnumColumn> enumColumns, EnumColumn target) {
        Map<String, Object> row = new LinkedHashMap<>();
        String sql = """
                select column_name, data_type
                  from information_schema.columns
                 where table_schema = 'public' and table_name = ?
                   and is_nullable = 'NO' and column_default is null
                 order by ordinal_position""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String column = rows.getString("column_name");
                    row.put(column, sample(rows.getString("data_type"), table, column, enumColumns));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the columns of " + table, e);
        }
        row.putAll(VALID_COMPANIONS.getOrDefault(table, Map.of()));
        row.putAll(VALID_COMPANIONS.getOrDefault(table + "." + target.column(), Map.of()));
        return row;
    }

    private static Object sample(String dataType, String table, String column, List<EnumColumn> enumColumns) {
        String allowed = enumColumns.stream()
                .filter(e -> e.table().equals(table) && e.column().equals(column))
                .map(EnumColumn::firstAllowedValue)
                .findFirst()
                .orElse(null);
        if (allowed != null) {
            return allowed;
        }
        return switch (dataType) {
            case "uuid" -> UUID.randomUUID();
            case "boolean" -> Boolean.FALSE;
            case "integer", "smallint" -> 1;
            case "bigint" -> 1L;
            case "numeric" -> java.math.BigDecimal.ONE;
            case "timestamp with time zone" -> java.sql.Timestamp.valueOf("2026-09-20 00:00:00");
            case "jsonb" -> "{}";
            default -> "x";
        };
    }

    private static String cloneOf(Connection connection, String table) {
        String clone = "probe_" + table;
        try (Statement statement = connection.createStatement()) {
            statement.execute("create temp table " + clone + " (like " + table + " including all)");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot clone " + table, e);
        }
        return clone;
    }

    /** Every varchar column whose own check constraint lists the values it accepts. */
    private static List<EnumColumn> readEnumColumns(Connection connection) throws SQLException {
        List<EnumColumn> columns = new ArrayList<>();
        String sql = """
                select c.conrelid::regclass::text as table_name,
                       a.attname                  as column_name,
                       c.conname                  as constraint_name,
                       pg_get_constraintdef(c.oid) as definition
                  from pg_constraint c
                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                  join information_schema.columns ic
                    on ic.table_schema = 'public'
                   and ic.table_name = c.conrelid::regclass::text
                   and ic.column_name = a.attname
                 where c.contype = 'c'
                   and c.connamespace = 'public'::regnamespace
                   and array_length(c.conkey, 1) = 1
                   and ic.data_type = 'character varying'
                 order by 1, 2""";
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                Matcher literals = LITERAL.matcher(rows.getString("definition"));
                while (literals.find()) {
                    values.add(literals.group(1));
                }
                if (!values.isEmpty()) {
                    columns.add(new EnumColumn(
                            rows.getString("table_name"),
                            rows.getString("column_name"),
                            rows.getString("constraint_name"),
                            values));
                }
            }
        }
        return columns;
    }

    /** Every check constraint that mentions a column, including the ones spanning several. */
    private static Map<String, Set<String>> readChecksByColumn(Connection connection) throws SQLException {
        Map<String, Set<String>> checks = new HashMap<>();
        String sql = """
                select c.conrelid::regclass::text as table_name,
                       a.attname                  as column_name,
                       c.conname                  as constraint_name
                  from pg_constraint c
                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey)
                 where c.contype = 'c'
                   and c.connamespace = 'public'::regnamespace""";
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                checks.computeIfAbsent(
                                rows.getString("table_name") + "." + rows.getString("column_name"),
                                key -> new LinkedHashSet<>())
                        .add(rows.getString("constraint_name"));
            }
        }
        return checks;
    }

    private static void rollbackTo(Connection connection, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot roll back to the savepoint", e);
        }
    }

    private record EnumColumn(String table, String column, String constraintName, List<String> allowedValues) {
        String firstAllowedValue() {
            return allowedValues.getFirst();
        }
    }

    /**
     * Enforcement is one half of the rule; the values are the other. This half pins the list of
     * enumerated columns and the values each one accepts to {@code schema/expected-enums.csv},
     * which was read against the logical model column by column. It is what fails when a check is
     * dropped — a dropped check stops being an enumeration and simply disappears from the probe
     * above — and when a value is quietly added to or removed from a list.
     */
    @Test
    void everyEnumeration_acceptsExactlyTheValuesOfTheLogicalModel() throws Exception {
        List<String> expected = new ArrayList<>();
        try (java.io.InputStream in = getClass().getResourceAsStream("/schema/expected-enums.csv");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    expected.add(line.trim());
                }
            }
        }

        List<String> actual;
        try (Connection connection = dataSource.getConnection()) {
            actual = readEnumColumns(connection).stream()
                    .map(column -> column.table() + "," + column.column() + "," + column.constraintName() + ","
                            + String.join("|", column.allowedValues()))
                    .sorted()
                    .toList();
        }

        assertThat(actual)
                .as("the enumerations of the schema, their constraint names and their value lists")
                .containsExactlyElementsOf(expected.stream().sorted().toList());
    }

    /** Guards against the clone losing the constraints the probe depends on. */
    @Test
    void aClonedTable_keepsItsCheckConstraints() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("create temp table probe_guard (like user_account including all)");
                try (ResultSet rows = statement.executeQuery(
                        "select count(*) from pg_constraint where conrelid = 'probe_guard'::regclass and contype = 'c'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(2);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
