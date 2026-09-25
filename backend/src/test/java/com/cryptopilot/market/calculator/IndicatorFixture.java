package com.cryptopilot.market.calculator;

import com.cryptopilot.market.model.IndicatorSnapshot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The golden indicator fixture: 1200 consecutive closed BTCUSDT 1h candles from the exchange, with the values an
 * independent library computed for them. Written once by {@code tools/golden/generate_indicator_fixture.py} and read
 * from the classpath, so no test reaches the network. An empty cell is a value the library has not got yet (warm-up).
 */
public final class IndicatorFixture {

    /** The fixture's resource path. */
    public static final String RESOURCE = "/golden/btcusdt-1h-talib.csv";

    /** One candle of the fixture and the reference values after it. */
    public record Row(Instant openTime, BigDecimal close, BigDecimal volume, IndicatorSnapshot expected) {}

    private IndicatorFixture() {}

    /** Every row, oldest first. */
    public static List<Row> rows() {
        try (InputStream in = IndicatorFixture.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String header = reader.readLine();
            if (!header.startsWith("openTime,close,volume,sma20,")) {
                throw new IllegalStateException("unexpected fixture header " + header);
            }
            List<Row> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(",", -1);
                Instant openTime = Instant.ofEpochMilli(Long.parseLong(cells[0]));
                IndicatorSnapshot expected = new IndicatorSnapshot(
                        openTime,
                        rows.size() + 1,
                        value(cells[3]),
                        value(cells[4]),
                        value(cells[5]),
                        value(cells[6]),
                        value(cells[7]),
                        value(cells[8]),
                        value(cells[9]),
                        value(cells[10]),
                        value(cells[11]),
                        value(cells[12]),
                        value(cells[13]),
                        value(cells[14]));
                rows.add(new Row(openTime, new BigDecimal(cells[1]), new BigDecimal(cells[2]), expected));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Double value(String cell) {
        return cell.isEmpty() ? null : Double.valueOf(cell);
    }
}
