package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pins every column of the schema to a reviewed contract.
 *
 * <p>The other schema tests assert rules — a primary key here, a check constraint there. This one
 * asserts the whole shape: {@code schema/expected-columns.csv} lists all 443 columns with their
 * type, numeric precision and nullability, and the test fails on any column added, removed or
 * changed. That is what catches the two mistakes a rule-based test cannot see: an attribute of the
 * logical model quietly dropped from the migration, and a column that no approved source asks for
 * quietly added to it — {@code setup_bias} on {@code technical_indicator}, for instance, which
 * belongs to an alignment item that is still pending and must not appear before it is approved.
 *
 * <p>The csv was generated from the migration and then read line by line against the logical model
 * plus the technical columns of TECHNICAL_DESIGN 5.5 and 6. Regenerating it to make a failure go
 * away defeats the test: the row is changed by hand, so that changing the shape of a table stays a
 * decision somebody wrote down.
 *
 * <p>Rule: SRS 3.1.5; TECHNICAL_DESIGN sections 5.4, 5.5 and 6.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaColumnContractTest {

    private static final String CONTRACT = "/schema/expected-columns.csv";

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everyColumn_matchesTheReviewedContract() throws IOException {
        Map<String, String> expected = readContract();
        Map<String, String> actual = readSchema();

        TreeSet<String> missing = new TreeSet<>(expected.keySet());
        missing.removeAll(actual.keySet());
        TreeSet<String> unexpected = new TreeSet<>(actual.keySet());
        unexpected.removeAll(expected.keySet());
        TreeSet<String> changed = new TreeSet<>();
        actual.forEach((column, shape) -> {
            String was = expected.get(column);
            if (was != null && !was.equals(shape)) {
                changed.add(column + ": contract says " + was + ", schema has " + shape);
            }
        });

        assertThat(missing)
                .as("columns the contract requires but the migration no longer creates")
                .isEmpty();
        assertThat(unexpected)
                .as("columns the migration creates that no reviewed source asks for")
                .isEmpty();
        assertThat(changed)
                .as("columns whose type, precision or nullability changed")
                .isEmpty();
    }

    @Test
    void contract_coversEveryTableOfTheSchema() throws IOException {
        TreeSet<String> tablesInContract = new TreeSet<>();
        readContract().keySet().forEach(column -> tablesInContract.add(column.substring(0, column.indexOf('.'))));

        List<String> tables = jdbc.sql("""
                        select table_name
                          from information_schema.tables
                         where table_schema = 'public'
                           and table_type = 'BASE TABLE'
                           and table_name <> 'flyway_schema_history'
                         order by table_name""").query(String.class).list();

        assertThat(tablesInContract).containsExactlyElementsOf(tables);
    }

    private Map<String, String> readSchema() {
        Map<String, String> columns = new LinkedHashMap<>();
        jdbc.sql("""
                        select table_name, column_name, data_type,
                               coalesce(numeric_precision::text, '') as numeric_precision,
                               coalesce(numeric_scale::text, '') as numeric_scale,
                               is_nullable
                          from information_schema.columns
                         where table_schema = 'public'
                           and table_name <> 'flyway_schema_history'
                         order by table_name, column_name""")
                .query()
                .listOfRows()
                .forEach(row -> columns.put(
                        row.get("table_name") + "." + row.get("column_name"),
                        shape(
                                String.valueOf(row.get("data_type")),
                                String.valueOf(row.get("numeric_precision")),
                                String.valueOf(row.get("numeric_scale")),
                                String.valueOf(row.get("is_nullable")))));
        return columns;
    }

    private Map<String, String> readContract() throws IOException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (InputStream in = getClass().getResourceAsStream(CONTRACT)) {
            assertThat(in)
                    .as("the column contract %s is on the test classpath", CONTRACT)
                    .isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            reader.readLine();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cell = line.split(",", -1);
                columns.put(cell[0] + "." + cell[1], shape(cell[2], cell[3], cell[4], cell[5]));
            }
        }
        return columns;
    }

    private static String shape(String dataType, String precision, String scale, String nullable) {
        String size = precision.isEmpty() ? "" : "(" + precision + "," + scale + ")";
        return dataType + size + ("YES".equals(nullable) ? " NULL" : " NOT NULL");
    }
}
