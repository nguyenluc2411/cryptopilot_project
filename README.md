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
cp deploy/.env.example deploy/.env          # set DB_PASSWORD and other local values
docker compose -f deploy/docker-compose.dev.yml up -d

cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Database: `localhost:5432`, Redis: `localhost:6379`, backend: `localhost:8080`. AI service and web app are started from their folders (instructions in each folder).

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
