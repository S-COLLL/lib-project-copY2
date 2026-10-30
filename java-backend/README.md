# SmartLib — Java (Spring Boot) backend

The SmartLib library management system as a **Java full-stack** application:

| Layer | Technology |
|---|---|
| Frontend | HTML, CSS, JavaScript (`../frontend`, served by Spring Boot) |
| Backend | Java 21, Spring Boot 4 (Spring Web MVC REST API under `/api`) |
| Database | MySQL 8+ via Spring JDBC (`JdbcTemplate`) and HikariCP |
| Security | JWT sign-in tokens (java-jwt), BCrypt password hashing (Spring Security Crypto) |
| Build | Maven (wrapper included: `mvnw` / `mvnw.cmd`) |

It uses the same database tables as the Node.js version (`../backend`) and answers every API request
with the same JSON, so existing data, passwords and the frontend work unchanged.

## Project structure

```
src/main/java/com/smartlib/
  SmartLibApplication.java     entry point (also schedules the hourly maintenance job)
  controller/                  REST endpoints: Auth, Catalog (books, authors, categories, shelves), Member,
                               Circulation (issues, returns, reservations), Finance (fines, payments),
                               Notification (+ settings), Report (+ AI assistant), Health
  service/                     business logic, one service per area, plus MaintenanceService
  library/                     shared lookups, bookkeeping (copy counts, reservation queue) and JSON mappers
  security/                    JWT service, login check for /api/**, CurrentUser with role checks
  config/                      MySQL connection pool (with SSL CA), error handling, headers / CORS / rate limits
  common/                      database helper, request input and JSON value helpers
```

## Run locally

Requirements: Java 21 or newer, and a MySQL database with the SmartLib tables
(create them once with `npm run setup` in the repository root, or import `../database/mysql/schema.sql`).

Settings are read from environment variables or from the `.env` file in the repository root —
the same keys as the Node.js version (`JWT_SECRET`, `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`,
`MYSQL_PASSWORD`, `MYSQL_DATABASE`, optional `MYSQL_SSL_CA`, `MYSQL_POOL_SIZE`, `PORT`).

```bash
cd java-backend
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
# or build a single runnable jar:
./mvnw package -DskipTests
java -jar target/smartlib.jar
```

Open http://localhost:3000 and sign in with `admin@library.com` / `Admin@123`.

## Run with Docker

```bash
docker build -f java-backend/Dockerfile -t smartlib .     # from the repository root
docker run -p 3000:3000 --env-file .env smartlib
```
