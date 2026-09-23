# CryptoPilot

Crypto Trading Support System — capstone project, FPT University, 09/2026 – 03/2027.

CryptoPilot helps traders analyse the Binance Spot and USDⓈ-M Futures markets, plan trades with explicit risk control, practise through simulated (paper) execution, and review results in a trading journal. It also provides a community forum, aggregated crypto news and an AI assistant.

CryptoPilot is a decision-support and paper-trading system. It does not execute real orders, does not connect to exchange accounts, does not hold funds and does not provide investment advice.

## Architecture

- **Backend:** modular monolith, Java 21 + Spring Boot 4.1 (REST + WebSocket/STOMP + scheduled jobs), package by module then by layer
- **AI Service:** Node.js + TypeScript, stateless
- **Database:** PostgreSQL 16 + TimescaleDB; Redis 7 as cache
- **Web:** React + TypeScript + Vite
- **Mobile:** Flutter
- **Deployment:** Docker Compose

## Repository layout

```
backend/      Maven project (one package per module, layers inside each module)
ai-service/   AI Service
web/          Web application and admin console
mobile/       Flutter application
deploy/       Docker Compose files and environment template
tools/        Development scripts and test data generators
```

## Getting started

Requirements: Docker Desktop, JDK 21 (Eclipse Temurin), Node.js LTS, Flutter SDK. Maven is not installed separately — use the wrapper in `backend/`.

```bash
cp deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.dev.yml up -d
```

Then fill in `deploy/.env`. Two keys are not optional:

- `DB_PASSWORD` — the password the compose file gives the database container.
- `JWT_SECRET` — at least 32 characters. The backend declares it with **no fallback**, so it refuses
  to start without one rather than starting with a key every reader of this repository holds.

`ADMIN_PASSWORD_HASH` and `DEMO_PASSWORD_HASH` are optional but usually wanted: leave them empty and
the seeded administrator and demo trader exist with no password that opens them. No credential is
committed, so these are the only way in.

Starting the backend:

```bash
# Windows
.\deploy\run-backend.ps1

# macOS / Linux
set -a && . ./deploy/.env && set +a
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The backend reads the OS environment, and nothing reads `deploy/.env` on its behalf — only docker
compose reads that file by itself. The script loads it and checks the two required keys before
starting. On Windows it also matters that the password hashes are full of `$`, which PowerShell
expands inside double quotes and silently turns into an empty string.

Database: `localhost:5432`, Redis: `localhost:6379`, backend: `localhost:8080`. AI service and web app are started from their folders (instructions in each folder).

Running the tests needs Docker (Testcontainers starts its own database) and a `JWT_SECRET`, which
the build supplies for the test run:

```bash
cd backend && ./mvnw clean verify
```

## Conventions

- Branches: `main` (release) ← `develop` (integration) ← `feature/T-xxx-short-name`
- Pull requests into `develop`, one reviewer, CI green
- Commits: [Conventional Commits](https://www.conventionalcommits.org/), e.g. `feat(trading): add liquidation price calculator`
- Secrets only in `deploy/.env` (never committed)

## Team

| Role | Member |
|---|---|
| Backend lead | |
| Backend | |
| Frontend / Mobile | |
| DevOps | |

Supervisor: Nguyễn Ngọc Lâm
