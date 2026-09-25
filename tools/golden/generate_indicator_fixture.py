"""Generates the golden-data fixture of the incremental indicator engine (T-025).

Candles: the latest closed BTCUSDT 1h candles of the public Binance Spot API (no key), downloaded once when the script
runs; the tests read the saved CSV and never touch the network.

Reference values: TA-Lib (https://ta-lib.org), an implementation independent of the engine, used here only. Each column
follows the variant of the technical design, section 7.2:

* SMA20, volume SMA20: TA-Lib SMA.
* EMA20/50/200: TA-Lib EMA, which seeds with the SMA of the first n closes and uses alpha = 2 / (n + 1).
* RSI14: TA-Lib RSI, Wilder smoothing seeded with the simple mean of the first 14 gains and losses.
* MACD(12, 26, 9): built from TA-Lib EMAs as the design defines it — EMA12 seeded at candle 12, EMA26 at candle 26,
  line = EMA12 - EMA26 from candle 26, signal = EMA9 of the line seeded with the mean of its first 9 values (candle 34).
  TA-Lib's own MACD function starts EMA12 later so both EMAs begin together; that is a different variant.
* Bollinger(20, 2): TA-Lib BBANDS with the population standard deviation and SMA middle band.

Every double is written with repr(), which round-trips exactly. The library versions and the candle range go to
btcusdt-1h-talib.versions.txt beside the CSV. Usage (network needed only here):

    pip install TA-Lib numpy
    python tools/golden/generate_indicator_fixture.py
"""
import csv
import datetime
import platform
import json
import math
import os
import urllib.request

import numpy as np
import talib

GOLDEN = os.path.join(os.path.dirname(__file__), '..', '..', 'backend', 'src', 'test', 'resources', 'golden')
OUT = os.path.join(GOLDEN, 'btcusdt-1h-talib.csv')
VERSIONS = os.path.join(GOLDEN, 'btcusdt-1h-talib.versions.txt')
COUNT = 1200


def closed_candles():
    url = 'https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=1000'
    rows = json.load(urllib.request.urlopen(url))
    first = rows[0][0]
    older = json.load(urllib.request.urlopen(url + '&endTime=' + str(first - 1)))
    rows = older + rows
    rows = rows[:-1]  # the last one is still forming
    return rows[-COUNT:]


def main():
    rows = closed_candles()
    open_time = [int(r[0]) for r in rows]
    close = np.array([float(r[4]) for r in rows])
    volume = np.array([float(r[5]) for r in rows])

    ema12 = talib.EMA(close, 12)
    ema26 = talib.EMA(close, 26)
    line = ema12 - ema26
    signal = np.concatenate([np.full(25, np.nan), talib.EMA(line[25:], 9)])
    upper, middle, lower = talib.BBANDS(close, 20, 2, 2, 0)
    columns = {
        'sma20': talib.SMA(close, 20),
        'ema20': talib.EMA(close, 20),
        'ema50': talib.EMA(close, 50),
        'ema200': talib.EMA(close, 200),
        'rsi14': talib.RSI(close, 14),
        'macdLine': line,
        'macdSignal': signal,
        'macdHistogram': line - signal,
        'bbUpper': upper,
        'bbMiddle': middle,
        'bbLower': lower,
        'volumeSma20': talib.SMA(volume, 20),
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', newline='', encoding='utf8') as out:
        writer = csv.writer(out)
        writer.writerow(['openTime', 'close', 'volume'] + list(columns))
        for i in range(len(rows)):
            values = ['' if math.isnan(columns[name][i]) else repr(float(columns[name][i])) for name in columns]
            writer.writerow([open_time[i], rows[i][4], rows[i][5]] + values)
    with open(VERSIONS, 'w', newline='\n', encoding='utf8') as out:
        out.write('generated: ' + datetime.date.today().isoformat() + '\n')
        out.write('source: Binance Spot GET /api/v3/klines BTCUSDT 1h (public, no key)\n')
        out.write('candles: ' + str(len(rows)) + ' closed, open times ' + str(open_time[0]) + ' to '
                  + str(open_time[-1]) + ' (epoch ms)\n')
        out.write('TA-Lib (Python wrapper): ' + talib.__version__ + '\n')
        out.write('TA-Lib (C library): ' + talib.__ta_version__.decode().split(' ')[0] + '\n')
        out.write('numpy: ' + np.__version__ + '\n')
        out.write('python: ' + platform.python_version() + '\n')
    print('wrote', len(rows), 'candles to', os.path.normpath(OUT), 'with TA-Lib', talib.__version__)


if __name__ == '__main__':
    main()
