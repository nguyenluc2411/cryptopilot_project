# Performance tests (T-094, D-164)

Apache JMeter 5.6.3 (Java 21) drives the HTTP targets of SRS 4.2.3; `check_targets.py` holds the result to them.

| File | What it is |
|---|---|
| `cryptopilot-srs-4.2.3.jmx` | The test plan: one sign-in, then the read API load and the risk calculation load |
| `check_targets.py` | Reads the result file, prints the 95th percentile of each request against its target, exit 1 on a miss |

## What is measured

| SRS 4.2.3 target | Requests (label prefix) | Load |
|---|---|---|
| Read API p95 ≤ 500 ms with 200 concurrent users | `read …`: market status, pairs, 500 candles, stats, analysis, plans, journal | `users` threads (200), 0.5–1.5 s think time |
| Risk calculation on SCR-17 p95 ≤ 300 ms | `calc risk SCR-17` (`POST /plans/calculate`) | `calcUsers` threads (20) |
| Performance Dashboard ≤ 2 s | `dashboard performance summary` | inside the read load |

Every request must answer 200; the error rate must stay under 1 %. The other rows of 4.2.3 (realtime delivery, candle
and indicator times, alert and fill delays, the AI answer) are timings inside the system, not request latencies; they
are measured by the backend's own tests and logs, not here.

## Running it

Run against staging, not a developer machine: on a laptop the load generator, the browser and the whole stack share
the same memory, and the figures say more about the laptop than about the system.

The rate limits of SRS 4.2.4 are per client address and per account, and one load generator is one address and one
account, so they would refuse the load long before 200 users. For the run only, raise them on staging
(`deploy/.env.staging`), restart the backend, and put the production values back afterwards:

```
RATE_LIMIT_LOGIN=1000
RATE_LIMIT_API=100000
RATE_LIMIT_GUEST=100000
```

Then, from a machine with JMeter (`<JMETER_HOME>` is where it is unpacked):

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar "<JMETER_HOME>\bin\ApacheJMeter.jar" -n `
  -t tools\perf\cryptopilot-srs-4.2.3.jmx `
  -Jprotocol=https -Jhost=<SITE_ADDRESS> -Jport=443 `
  -Jemail=demo.trader@cryptopilot.invalid -Jpassword=<demo password> `
  -Jusers=200 -Jrampup=60 -Jduration=300 -JcalcUsers=20 `
  -l perf-results\results.jtl -e -o perf-results\report
python tools\perf\check_targets.py perf-results\results.jtl perf-results\summary.md
```

`perf-results\report\index.html` is the HTML dashboard attached to T-094 (percentiles, throughput, errors over time),
`summary.md` the pass or miss of each target. A miss is either fixed or recorded as a deviation in CURRENT_STATE.md.
The password is passed on the command line only; never write it into the plan or a committed file.
