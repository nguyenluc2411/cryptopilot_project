# deploy/

| File | What it is |
|---|---|
| `docker-compose.dev.yml` | PostgreSQL + TimescaleDB and Redis for local development |
| `.env.example` | The environment template; copy it to `deploy/.env` (never committed) |
| `run-backend.ps1` | Loads `deploy/.env` and starts the backend locally (Windows) |
| `demo/load-wf01.ps1`, `demo/wf01.json` | The WF-01 demonstration data loader (DEMO-01), below |

## WF-01 demonstration data

The demonstration has to start from the same state every time, on a developer's machine and on staging, and nobody
should type SQL by hand to get there. It comes in two parts.

**1. Accounts (Flyway, `backend/src/main/resources/db/demo`).** `demo.free`, `demo.pro` and `demo.premium`
(`@cryptopilot.invalid`) with their tier, risk profile and default capital, plus the older demo accounts. Flyway only
applies that location when the profile asks for it:

| Environment | `SPRING_PROFILES_ACTIVE` | `CRYPTOPILOT_ENVIRONMENT` | Demo accounts |
|---|---|---|---|
| Developer machine | `dev` | not needed (`development`) | yes, `dev` already lists `db/demo` |
| Staging | `prod,demo` | `staging`, **required** | yes, the `demo` profile adds `db/demo` |
| Production | `prod` | not needed (`production` under `prod`) | **no**, and nothing in `prod` can add it |

**Production guard.** The backend refuses to start when the `demo` profile is active and `CRYPTOPILOT_ENVIRONMENT`
(property `cryptopilot.environment`) is anything but `development` or `staging`. Under `prod` it defaults to
`production`, so `prod,demo` without `CRYPTOPILOT_ENVIRONMENT=staging` stops at start-up, before Flyway runs, with a
message naming both variables. A production deployment that lists `demo` by mistake therefore never writes a demo
account.

The accounts sign in with the password that `DEMO_PASSWORD_HASH` was made from (a BCrypt hash at strength 12 in the
backend's environment, see `.env.example`). No password is in the repository.

**2. Pairs, leverage brackets and the demo plans (`demo/load-wf01.ps1`).** These cannot be Flyway rows. The pairs are
created by the symbol synchronisation after Flyway has run, and a plan is only valid when the plan service has worked it
out against the current price. So the script calls the running backend's API, the same way an administrator and a
Trader would on their screens, and the same checks run:

- as the administrator: the Futures leverage brackets of BTCUSDT and ETHUSDT (copied from Binance's public table,
  source and date in `demo/wf01.json`), then each pair ACTIVE on Spot and Futures with a 125x ceiling;
- as `demo.free`: Spot LIMIT plans far below the price, saved and activated, until the account holds 3 ACTIVE plans
  (the FREE limit, so a fourth activation answers MSG27).

The script only writes what differs from `demo/wf01.json`. Running it again changes nothing, and it restores the
starting state when something was changed during a rehearsal, for example a cancelled demo plan or a pair turned off.

### Running it

The backend must be up. On a fresh database, give the symbol synchronisation a minute to create the pairs. The script
needs the two plain passwords in its environment (never in a file of the repository):

```powershell
# Windows PowerShell 5.1 or PowerShell 7
$env:ADMIN_PASSWORD = '<password of admin@cryptopilot.invalid>'
$env:DEMO_PASSWORD  = '<password of the demo accounts>'

.\deploy\demo\load-wf01.ps1                                         # local backend, http://localhost:8080
.\deploy\demo\load-wf01.ps1 -BaseUrl https://<staging host>         # staging
```

```bash
# Linux / macOS (PowerShell 7)
ADMIN_PASSWORD='...' DEMO_PASSWORD='...' pwsh deploy/demo/load-wf01.ps1 -BaseUrl https://<staging host>
```

Options: `-BaseUrl` (or `CRYPTOPILOT_BASE_URL`), `-DataFile`, `-PriceWaitSeconds` (default 120: how long to wait for
the first Spot price of a pair this run switched on), `DEMO_ADMIN_EMAIL` (default `admin@cryptopilot.invalid`).
The script stops with a non-zero exit code and a message on the first problem, such as a pair the synchronisation has
not created yet, no price, a wrong password, or a plan the backend refused. Fix the cause and run it again.

### Why a profile plus an API script

The repository has no staging deployment files yet (T-096). Staging will run the same image as production with
environment variables, and a Spring profile is the one switch that setup already has. Flyway can therefore load the
accounts there exactly as it does in dev, and production cannot reach them. Everything that depends on live market
data goes through the API, so the demo data is checked by the same validation as real use and needs no database
access from the machine running the demonstration.

`DemoDatasetTest`, `DemoProfileTest`, `DemoEnvironmentGuardTest` and `DemoLoaderTest` (backend) prove the accounts, the profile
split, the production guard, the bracket data and the script, including that a second run changes nothing.
