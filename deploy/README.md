# deploy/

| File | What it is |
|---|---|
| `docker-compose.dev.yml` | PostgreSQL + TimescaleDB and Redis for local development; with `--profile app` also the backend, the AI Service and the web app's Vite server in containers (T-004) |
| `docker-compose.yml` | Staging: the whole system on one host behind Caddy with HTTPS (T-004, T-096), below |
| `caddy/Caddyfile` | The staging edge: `/api`, `/ws` and the health check to the backend, the rest to the web app |
| `.env.example` | The environment template; copy it to `deploy/.env` (dev) or `deploy/.env.staging` (staging), never committed |
| `run-backend.ps1` | Loads `deploy/.env` and starts the backend locally (Windows) |
| `demo/load-wf01.ps1`, `demo/wf01.json` | The WF-01 demonstration data loader (DEMO-01), below |
| `demo/load-paper.ps1` | The demo trading data of the defence (TR-33): demo.pro's paper account with Spot and Futures activity; run after `load-wf01.ps1` with `DEMO_PASSWORD` set |

## Containers (T-004)

Each component has its own image: `backend/Dockerfile` (Maven build, then the JRE 21 runtime; tests are run by CI, not
here), `ai-service/Dockerfile` (Node 22) and, in the web repository, `Dockerfile` (Vite build served by nginx). Every
image has a health check: the backend's readiness (`/actuator/health/readiness`), the AI Service's `/health`, the web
server's `/healthz`.

### Everything on a developer machine

```powershell
docker compose -f deploy/docker-compose.dev.yml --profile app up -d --build
```

The backend runs the `dev` profile against the containers' database and Redis, the web app runs Vite at
http://localhost:5173. Stop the IDE's backend, AI Service and Vite first: the containers take ports 8080, 7070 and
5173. Without `--profile app` only the database and Redis start, as before.

## Staging

One host (EC2, T-096) runs `docker-compose.yml`: the database, Redis, the AI Service, the backend under `prod,demo`
with `CRYPTOPILOT_ENVIRONMENT=staging`, the web app and Caddy. Only Caddy publishes ports (80 and 443); it obtains the
HTTPS certificate by itself, and the web app's production build accepts only `https` and `wss` addresses.

1. Point a host name at the server. Without a domain, `<public IP>.sslip.io` resolves to the IP and gets a certificate.
2. On the server, clone this repository and the web repository side by side (or set `WEB_DIR`).
3. `cp deploy/.env.example deploy/.env.staging` and fill it in. Required: `SITE_ADDRESS`, `DB_PASSWORD`, `JWT_SECRET`,
   `AI_SERVICE_SHARED_SECRET`, `LLM_API_KEY`, `SMTP_HOST`, `SMTP_FROM`, the three `CLOUDINARY_*`; the demonstration
   also needs `ADMIN_PASSWORD_HASH` and `DEMO_PASSWORD_HASH`. Compose names any missing one and stops. SePay also
   needs `SEPAY_CHECKOUT_URL` and `SEPAY_API_URL` (the sandbox ones are in `application-dev.yml`): `prod` leaves them
   empty, which switches SePay off.
4. Start it:

   ```bash
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env.staging up -d --build
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env.staging ps     # every service healthy
   ```

5. Load the demonstration data from a developer machine with `demo/load-wf01.ps1 -BaseUrl https://<SITE_ADDRESS>`
   (below): it only calls the API.

### Continuous deployment (T-096)

The staging host deploys itself: a systemd timer runs `deploy/cd/cd.sh` every 3 minutes. When the tip of `develop`
in this repository or in the web repository has moved and GitHub reports that commit's checks as finished and green,
it dumps the database to `~/cryptopilot/backups` (the last five are kept), checks the new commits out, runs
`docker compose ... up -d --build` and waits until every service is healthy. If that fails it goes back to the
previous commits and does not try the failed one again; the next commit on `develop` is tried as usual. A commit
whose CI is red is not deployed but asked about again on every pass, so re-running a flaky job is enough. A rollback
does not undo Flyway migrations: restore the dump taken just before when a migration has to go.

Nothing is stored on GitHub and no port is opened for it, so a repository admin is not needed: both repositories are
public, the host only reads them. Install once on the host, after step 4 with the two repositories cloned (not copied)
into `~/cryptopilot`:

```bash
deploy/cd/install.sh                                  # the timer, settings in /etc/cryptopilot-cd.env
journalctl -u cryptopilot-cd -f                       # what each pass did; also ~/cryptopilot/.cd/deploy.log
deploy/cd/cd.sh --force                               # deploy the current tips now, without waiting for CI
```

`CD_BE_FALLBACK` names a branch to follow while `develop` has no `deploy/docker-compose.yml` yet; leave it empty.

### Backup and restore (T-096, SRS 4.2.2)

`install.sh` also enables `cryptopilot-backup.timer`: every day at 19:00 UTC (02:00 in Vietnam; a run missed while
the host was off happens at boot) `deploy/backup/backup.sh` writes a custom-format dump to
`~/cryptopilot/backups/daily-*.dump`, checks that `pg_restore` can read it and keeps the last seven. The deployment adds
its own `pre-deploy-*.dump` before each pass.

```bash
deploy/backup/restore.sh --drill [dump]   # into a throw-away container, compared table by table with the live one
deploy/backup/restore.sh --live dump      # replaces the live database; prints the recovery time
```

TimescaleDB needs `timescaledb_pre_restore()` / `timescaledb_post_restore()` around a **sequential** `pg_restore`
(the script does both); a parallel restore (`-j`) breaks its catalog.

Drill of 2026-11-26 on the EC2 host (m7i-flex.large, dump 136 MB, 68 tables, Flyway V76): the throw-away restore took
31 s and matched the live database except the rows the market streams wrote after the dump; the live restore took
42 s for the database and 13 s more until the backend was healthy, **RTO 55 s**. The RPO is the backup interval: one
day, or the last deployment if that was later.

The dumps live on the host's own disk. Copy one off the host (`scp`) before anything that replaces the disk.

The payment gateways' IPN addresses are `https://<SITE_ADDRESS>/api/v1/billing/ipn/sepay` and `.../momo`. The memory
limits suit a host with 8 GB (backend 2.5 GB, database 1.5 GB); `BACKEND_MEM_LIMIT` and `DB_MEM_LIMIT` change them.

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
