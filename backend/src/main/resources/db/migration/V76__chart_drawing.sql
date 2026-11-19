-- T-119 (D-149): the drawings a Trader puts on a pair's chart (trend lines, zones, Fibonacci levels, trade ideas,
-- harmonic and Elliott wave patterns, notes). A Trader keeps several layouts per pair, each with its own drawings; a
-- layout belongs to the pair, so the Spot and Futures charts of a symbol share it at every timeframe. The anchors
-- (candle open time and price) and the style are JSON the application checks before writing; the module reads no
-- other module's tables.

-- A named set of drawings on one pair. seq_no numbers the Trader's layouts on the pair (the chart shows "Copy n" while
-- layout_name is empty); a new layout takes the smallest number still free.
CREATE TABLE chart_layout (
    layout_id    uuid         NOT NULL,
    user_id      uuid         NOT NULL,
    pair_id      uuid         NOT NULL,
    seq_no       integer      NOT NULL,
    layout_name  varchar(60),
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_chart_layout PRIMARY KEY (layout_id),
    CONSTRAINT fk_chart_layout_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_chart_layout_pair FOREIGN KEY (pair_id) REFERENCES crypto_pair (pair_id),
    CONSTRAINT uq_chart_layout_user_pair_seq UNIQUE (user_id, pair_id, seq_no)
);

-- The pair index serves the foreign key; the unique key already serves the Trader's list of one pair.
CREATE INDEX idx_chart_layout_pair ON chart_layout (pair_id);

CREATE TABLE chart_drawing (
    drawing_id   uuid         NOT NULL,
    user_id      uuid         NOT NULL,
    layout_id    uuid         NOT NULL,
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
    -- Removing a layout removes its drawings.
    CONSTRAINT fk_chart_drawing_layout FOREIGN KEY (layout_id) REFERENCES chart_layout (layout_id) ON DELETE CASCADE,
    CONSTRAINT ck_chart_drawing_type CHECK (drawing_type IN ('TREND_LINE', 'HORIZONTAL_LINE', 'HORIZONTAL_RAY',
        'VERTICAL_LINE', 'RECTANGLE', 'FIB_RETRACEMENT', 'LONG_POSITION', 'SHORT_POSITION', 'TEXT', 'PARALLEL_CHANNEL',
        'FIB_EXTENSION', 'PRICE_RANGE', 'DATE_RANGE', 'ARROW', 'XABCD_PATTERN', 'CYPHER_PATTERN', 'ABCD_PATTERN',
        'THREE_DRIVES_PATTERN', 'HEAD_AND_SHOULDERS', 'TRIANGLE_PATTERN', 'ELLIOTT_IMPULSE_WAVE',
        'ELLIOTT_CORRECTION_WAVE', 'ELLIOTT_TRIANGLE_WAVE', 'ELLIOTT_DOUBLE_COMBO', 'ELLIOTT_TRIPLE_COMBO'))
);

-- The chart of one layout loads its drawings; the user index serves the foreign key.
CREATE INDEX idx_chart_drawing_layout ON chart_drawing (layout_id);
CREATE INDEX idx_chart_drawing_user ON chart_drawing (user_id);
