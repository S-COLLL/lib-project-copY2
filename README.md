# Library Management App
SmartLib

A full-stack library system: catalogue with exact shelf locations, issue / return / renew, reservations with hold queue, automatic late fines (₹10/day), payments, reports, a rule-based AI assistant and notifications.

The interface is the SmartLib UI (dark navy / teal / gold, Inter). It is English by default, and Hungarian can be selected in **Settings → Appearance → Language / Nyelv**. Money is shown in **Indian rupees (₹)**.

- **Frontend:** HTML5, CSS3, vanilla JavaScript ES modules, Chart.js. No framework, no build step.
- **Backend:** Node.js 18+, Express 4, MySQL 8 (InnoDB, utf8mb4) via `mysql2` with a pool of 10.
- **Security:** JWT (7 days), bcrypt (`bcryptjs`), Helmet, CORS, express-rate-limit, express-validator, `?` placeholders in every query.
- **Java backend (alternative):** the same API in **Java 21 + Spring Boot 4** with Spring JDBC — see [java-backend/README.md](java-backend/README.md). It uses the same MySQL tables and serves the same frontend.

## Run locally

```bash
npm install
copy .env.example .env      # then set MYSQL_PASSWORD (and JWT_SECRET)
npm run setup               # creates database library_app (13 tables) + demo library: 41 books, 18 members, 36 loans …
npm start                   # http://localhost:3000
```

`npm run setup` wipes and recreates the database, so run it only for a fresh install. To reload just the demo data, run `npm run seed:mysql`. You can also import `database/mysql/schema.sql` by hand (`mysql -u root -p < database/mysql/schema.sql`).

If port 3000 is busy, set `PORT=3100` (any free port) in `.env`.

## Put it online (Vercel + cloud MySQL)

`vercel.json` serves `frontend/` from Vercel's CDN and runs the API (`api/index.js` → the same Express app) as a serverless function in **Mumbai (`bom1`)**.

1. **Database:** create a cloud MySQL database, for example a free **MySQL** service at [aiven.io](https://aiven.io). Note the Host, Port, User and Password, and copy the **CA certificate**.
2. **Tables and demo data** (one time, from your PC): run `npm run setup` with `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_DATABASE` and `MYSQL_SSL_CA` pointing at the cloud database.
3. **App:** at [vercel.com](https://vercel.com), sign in with GitHub, choose **Add New → Project**, and import this repository. Under **Environment Variables**, add:

   | Variable | Value |
   |---|---|
   | `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD` | from the database provider |
   | `MYSQL_DATABASE` | e.g. `defaultdb` (Aiven) |
   | `MYSQL_SSL_CA` | the whole CA certificate text |
   | `JWT_SECRET` | a long random string |
   | `MYSQL_POOL_SIZE` | `3` |
   | `TRUST_PROXY` | `1` |
   | `NODE_ENV` | `production` |

   Click **Deploy**. Your link is `https://<project>.vercel.app`.

On Vercel, the overdue/expiry upkeep runs on the first API request each hour instead of on a timer, and "Back up now" can't write files (the filesystem is read-only). `render.yaml` is also included if you prefer Render (it runs `AUTO_SEED`, so step 2 isn't needed there).

## Demo logins (email or numeric user ID)

| Role | Email | User ID | Password |
|---|---|---|---|
| Admin | admin@library.com | 1 | Admin@123 |
| Librarian | librarian@library.com | 2 | Librarian@123 |
| Staff | staff@library.com | 3 | Staff@123 |
| Student | student@library.com | 4 | Student@123 |

## Environment (`.env`)

| Variable | Purpose |
|---|---|
| `PORT` | HTTP port (default 3000) |
| `JWT_SECRET` | Token signing key — the server refuses to start without it |
| `JWT_EXPIRES_IN` | Token lifetime (default `7d`) |
| `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_DATABASE` | Database connection (`library_app`) |
| `AUTO_SEED` | `true` creates the tables (if missing) and loads the demo data on startup when the database is empty |
| `MYSQL_SSL_CA` | Cloud MySQL CA certificate: the PEM text, or a path to the `.pem` file. Turns on verified TLS |
| `TRUST_PROXY` | `1` behind a hosting proxy (Render), so rate limiting sees real client addresses |

## Project layout

```
backend/   server.js · config/db.js · routes/ (15 routers) · controllers/ · middleware/ · utils/ (lib, maintenance)
database/  mysql/schema.sql · mysql/seed.js
frontend/  16 HTML pages · css/ · js/ (app shell, api client, i18n, one module per page, demo/ in-browser backend)
docs/      PROJECT_DOCUMENTATION.md · MANUAL_TEST_SCRIPT.md
```

Full design, API and test results: [docs/PROJECT_DOCUMENTATION.md](docs/PROJECT_DOCUMENTATION.md).

http://192.168.1.6:3001.
