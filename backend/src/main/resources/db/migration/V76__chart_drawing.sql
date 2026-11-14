-- T-119 (D-149): the drawings a Trader puts on a pair's chart (trend lines, zones, Fibonacci levels, trade ideas,
-- notes). A drawing belongs to the pair, so the Spot and Futures charts of a symbol share it at every timeframe. The
-- anchors (candle open time and price) and the style are JSON the application checks before writing; the module
-- reads no other module's tables.
CREATE TABLE chart_drawing (
    drawing_id   uuid         NOT NULL,
    user_id      uuid         NOT NULL,
    pair_id      uuid         NOT NULL,
    drawing_type varchar(32)  NOT NULL,
    points       jsonb        NOT NULL,
    style        jsonb        NOT NULL,
    label_text   varchar(500),
    is_locked    boolean      NOT NULL DEFAULT false,
    is_hidden    boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_chart_drawing PRIMARY KEY (drawing_id),
    CONSTRAINT fk_chart_drawing_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_chart_drawing_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT ck_chart_drawing_type CHECK (drawing_type IN ('TREND_LINE', 'HORIZONTAL_LINE', 'HORIZONTAL_RAY',
        'VERTICAL_LINE', 'RECTANGLE', 'FIB_RETRACEMENT', 'LONG_POSITION', 'SHORT_POSITION', 'TEXT', 'PARALLEL_CHANNEL',
        'FIB_EXTENSION', 'PRICE_RANGE', 'DATE_RANGE', 'ARROW'))
);

-- The chart of one pair loads the Trader's drawings on it.
CREATE INDEX idx_chart_drawing_user_pair ON chart_drawing (user_id, pair_id);
CREATE INDEX idx_chart_drawing_pair ON chart_drawing (pair_id);
