# Setup Guide: from a fresh laptop to a running bank

This guide assumes you have **never installed any of this**. Follow it top to bottom. Allow about 45 minutes the first time, most of it downloads.

Works on Windows 10/11, macOS and Linux. Commands marked 🪟 are Windows only, 🍎 macOS only, 🐧 Linux only.

---

## 0. What you are about to run

```
 your laptop
 ┌──────────────────────────────────────────────────────────┐
 │  Docker containers (started by docker compose)           │
 │    PostgreSQL :5433    Kafka :9092    Keycloak :8180     │
 │                                                          │
 │  Spring Boot app :8080   (you start it with Maven)       │
 └──────────────────────────────────────────────────────────┘
```

Docker runs the three "infrastructure" programs so you do not install them one by one. The Java application itself runs directly on your laptop.

**Hardware:** 8 GB RAM minimum (Keycloak + Kafka + Postgres + Java together use about 3 GB), 10 GB free disk.

---

## 1. Install the tools (one time)

| Tool | Version | Why you need it |
|---|---|---|
| **Git** | any recent | download/upload the code, GitHub |
| **JDK (Java)** | **21** | compile and run the application |
| **Maven** | 3.9+ | builds the project and downloads libraries |
| **Docker Desktop** (Windows/macOS) or **Docker Engine + Compose plugin** (Linux) | recent | runs PostgreSQL, Kafka, Keycloak, and the test database |
| **curl** and **Python 3** | any | used by `scripts/get-token.sh` and `scripts/demo.sh` |
| An editor, e.g. **IntelliJ IDEA Community** or **VS Code** (+ "Extension Pack for Java") | any | reading/editing the code |

### 🪟 Windows

1. **Git for Windows**: https://git-scm.com/download/win. Accept the defaults. It also installs **Git Bash**, a terminal that can run the `.sh` scripts. **Use Git Bash for every command in this guide.**
2. **JDK 21**: install *Eclipse Temurin 21* from https://adoptium.net (choose the `.msi`, tick "Set JAVA_HOME" and "Add to PATH" in the installer).
3. **Maven**: download the *Binary zip* from https://maven.apache.org/download.cgi, unzip to `C:\tools\apache-maven-3.9.x`, then add `C:\tools\apache-maven-3.9.x\bin` to your **PATH** (Start → "Edit the system environment variables" → Environment Variables → Path → New). Alternatively `winget install Apache.Maven`.
4. **Docker Desktop**: https://www.docker.com/products/docker-desktop. During install keep "Use WSL 2" ticked, reboot if asked, then **start Docker Desktop and wait until it says "Engine running"**.
5. **Python 3**: https://www.python.org/downloads (tick "Add python.exe to PATH"). `curl` is already built into Windows 10/11 and Git Bash.

### 🍎 macOS

```bash
# install Homebrew first if you do not have it: https://brew.sh
brew install git openjdk@21 maven python
brew install --cask docker          # then open the Docker app once and wait for "Engine running"
# make Java 21 visible:
sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

### 🐧 Linux (Ubuntu / Debian)

```bash
sudo apt update
sudo apt install -y git openjdk-21-jdk maven python3 curl
# Docker Engine + Compose plugin: follow https://docs.docker.com/engine/install/ubuntu/
sudo usermod -aG docker $USER      # then log out and back in so "docker" works without sudo
```

### Check everything is installed

Open a **new** terminal (Git Bash on Windows) and run:

```bash
git --version          # git version 2.x
java -version          # must say 21.x
mvn -version           # must say Apache Maven 3.9.x  AND  Java version: 21.x
docker --version
docker compose version
python3 --version      # on Windows use:  python --version
curl --version
```

If `java -version` shows 17 or 11, Maven will fail with "release version 21 not supported". Fix your `JAVA_HOME`/PATH so Java 21 comes first.

---

## 2. Get the code

```bash
git clone https://github.com/<your-username>/digital-banking-core.git
cd digital-banking-core
```

(If you received a zip instead: unzip it, open a terminal in the unzipped folder.)

---

## 3. Start the infrastructure

Make sure Docker Desktop is running, then in the project folder:

```bash
docker compose up -d
```

The first run downloads about 1.2 GB of images. Then check:

```bash
docker compose ps
```

You want `bank-postgres` to say **healthy** and `bank-kafka` and `bank-keycloak` to say **running**. **Keycloak needs about 60 to 90 seconds to boot.** Check that it is ready:

```bash
curl -s http://localhost:8180/realms/bank/.well-known/openid-configuration | head -c 200
```

If you see JSON starting with `{"issuer":"http://localhost:8180/realms/bank"...`, Keycloak is ready, and the realm `bank` with its demo users was imported automatically. If you see "Connection refused" or "Realm does not exist", wait 30 seconds and try again.

| Service | URL / port | Login |
|---|---|---|
| PostgreSQL | `localhost:5433`, db `bank` | user `bank`, password `bank` |
| Kafka | `localhost:9092` | none |
| Keycloak admin console | http://localhost:8180 | `admin` / `admin` |

> **Port already in use?** Edit the left-hand number in `docker-compose.yml` (for example `"5433:5432"` → `"5434:5432"`) and set the matching environment variable when you start the app (`DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KEYCLOAK_ISSUER_URI`; see `src/main/resources/application.yml`).

---

## 4. Run the automated tests first

```bash
mvn test
```

What happens: Maven downloads the libraries (several minutes the first time), compiles the code, starts a **separate temporary PostgreSQL in Docker** (Testcontainers; this is why Docker must be running), and runs the 32 integration tests, including the parallel-transfer and idempotency tests. Expected end of output:

```
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The HTML-style results are in `target/surefire-reports/`. **Take a screenshot of this output for your presentation.**

> ### ⚠️ Read this: first-run honesty note
> The Java code in this repository was written and reviewed in a sandbox that could **not download Maven libraries**, so it has **never been compiled on a machine with internet**. The SQL schema and the transfer/locking logic were executed for real against PostgreSQL 16 (see `verification/`), but `mvn test` is the first time the Java is compiled. Typos in a project of this size are possible. If you get a compile error:
> 1. Copy the **first** `[ERROR]` line (file name, line number, message).
> 2. Send it to me (or fix it; it is usually an import or a missing semicolon), then run `mvn test` again.
>
> Do this **today**, not on deadline day.

---

## 5. Start the application

```bash
mvn spring-boot:run
```

Wait for a line like `Started BankCoreApplication in 6.2 seconds`. Flyway prints that it applied migrations V1 to V4 the first time. Leave this terminal open (it is the server's log). Check it is alive in a second terminal:

```bash
curl http://localhost:8080/actuator/health        # {"status":"UP"}
```

Open **http://localhost:8080/swagger-ui.html** in a browser. This is the interactive API documentation generated from the code (OpenAPI). The raw OpenAPI file is at http://localhost:8080/v3/api-docs.

---

## 6. Use it

### 6a. The fast way: run the scripted demo

In a **second terminal** (Git Bash on Windows) in the project folder:

```bash
bash scripts/demo.sh
```

It plays the whole story in 18 steps: customers sign in, are blocked until KYC is approved, a compliance officer approves them, a teller deposits cash, Alice transfers money to Bob, a **retry does not charge twice**, a **reused key with a different amount is refused**, overdraft and fraud rules trigger, statements and notifications appear, and the **reconciliation proves the books balance**. Every step prints the HTTP status and the response, which also makes it the ideal script for your screen recording.

### 6b. The manual way: Swagger UI

1. Get a token for a user:
   ```bash
   bash scripts/get-token.sh alice      # prints a long string starting with eyJ...
   ```
2. In Swagger UI click **Authorize**, paste the token (without the word `Bearer`), click **Authorize**, **Close**.
3. Try `GET /api/v1/customers/me` → *Try it out* → *Execute*.

Demo users (password for all: `password`):

| User | Role | What to try |
|---|---|---|
| `alice`, `bob` | customer | profile, KYC submit, open account, transfer, statement |
| `tina` | teller | deposit / withdraw cash |
| `carl` | compliance | approve / reject KYC, view fraud alerts |
| `admin1` | admin | reconciliation, run the statement job |

Tokens expire after a few minutes. If you get `401`, fetch a new one.

### 6c. Look inside the database

```bash
docker exec -it bank-postgres psql -U bank -d bank
```
```sql
select account_number, balance from accounts;
select account_id, amount, balance_after from ledger_entries order by id desc limit 10;
select sum(amount) from ledger_entries;      -- always 0.00
select topic, event_type, published_at from outbox_events order by id desc limit 5;
\q
```

### 6d. Watch the Kafka events

```bash
docker exec -it bank-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic bank.transactions.completed --from-beginning
```
Make a transfer in another terminal and a JSON line appears.

---

## 7. Measuring performance (for the "results" slide)

The numbers in `verification/` come from a small cloud sandbox and a Python replay, so **do not present them as your application's performance**. Measure your own:

1. Start the stack and the app.
2. Install a load tool, e.g. `k6` (https://k6.io/docs/get-started/installation/) or `hey`.
3. Fire a few thousand `POST /api/v1/transfers` between several account pairs with unique `Idempotency-Key` values and a fresh token (tokens expire, so use a long-lived test token: in Keycloak admin → Realm settings → Tokens → Access Token Lifespan → 1 hour).
4. Record requests per second and p50/p95/p99 latency, then run `GET /api/v1/admin/reconciliation` and screenshot `"healthy": true`.

Always finish a load test with the reconciliation check. "Fast **and** the books still balance" is your headline.

---

## 8. Stop, restart, reset

| Goal | Command |
|---|---|
| Stop the app | `Ctrl + C` in its terminal |
| Stop containers, keep data | `docker compose stop` |
| Start them again | `docker compose start` |
| **Delete all data and start clean** | `docker compose down -v` then `docker compose up -d` |

---

## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `release version 21 not supported` | Maven is using an older Java. Fix `JAVA_HOME` so it points to JDK 21. |
| `Cannot connect to the Docker daemon` / Testcontainers error | Docker Desktop is not running. Start it and wait for "Engine running". |
| Testcontainers: `client version 1.xx is too old. Minimum supported API version is 1.44` | Docker Engine 29+ dropped old API versions and Testcontainers 1.x (used by Spring Boot 3.5) asks for one. The test base class already sets `api.version=1.44` to work around it. If your Docker is *older* than 25 and says `client version 1.44 is newer`, run `mvn test -Dapi.version=1.43` instead. |
| `mvn` cannot download libraries / timeouts | Company/campus proxy or VPN. Try another network or configure Maven's proxy in `~/.m2/settings.xml`. |
| App fails: `Connection to localhost:5433 refused` | `docker compose ps`: Postgres not healthy yet, or Docker not running. |
| `401 Unauthorized` on every call | Token expired or missing. Run `scripts/get-token.sh` again. The `Authorize` button wants the token **without** the word "Bearer". |
| `401` and the app log says `Unable to resolve the Issuer` | Keycloak not ready, or the app cannot reach `http://localhost:8180`. Check the discovery URL in step 3. |
| `403` on an endpoint | Your user lacks the role (customer vs teller vs compliance vs admin). See the table in 6b. |
| `403 KYC_REQUIRED` | Working as designed: the customer's KYC must be approved by `carl` first. |
| Kafka warnings `Connection to node -1 could not be established` | Kafka container still starting or not running. Transfers still succeed: events wait in the `outbox_events` table and are sent when Kafka is back. |
| `demo.sh`: `python3: command not found` (Windows) | Run `alias python3=python` in Git Bash, or install Python and re-open the terminal. |
| `demo.sh` shows `429 FRAUD_VELOCITY` earlier than expected | You ran the demo twice within a minute; the 5-per-60-seconds rule counts earlier transfers. Wait a minute. |
| Keycloak container exits | Not enough RAM. Close other apps, give Docker Desktop at least 4 GB (Settings → Resources). |

---

## 10. Publish to GitHub

1. Create an account at https://github.com if you do not have one.
2. Click **New repository** → name `digital-banking-core` → leave it **empty** (no README, no .gitignore) → Create.
3. In the project folder:
   ```bash
   git remote add origin https://github.com/<your-username>/digital-banking-core.git
   git branch -M main
   git push -u origin main
   ```
   GitHub will ask you to log in. Use a *Personal Access Token* as the password (GitHub → Settings → Developer settings → Personal access tokens → Fine-grained token, with *Contents: Read and write* on this repo), or sign in via the browser prompt of Git Credential Manager.
4. Copy the repository URL. **That is your GitHub link.**

The commit history of the zip is already organised as a series of meaningful commits (schema → ledger → transfers → security → events → KYC/fraud → tests → docs), which is what "clean commits" in the brief asks for. Do not squash them. The commits in the zip carry a placeholder author name, so **put your own name on them before you push**:
```bash
git config user.name  "Your Name"
git config user.email "you@example.com"        # the e-mail of your GitHub account
git rebase -r --root --exec "git commit --amend --no-edit --reset-author"
```
(Run this once, before the first push.) Please also **commit under your own name from now on**: set it once for all projects with
```bash
git config --global user.name  "Your Name"
git config --global user.email "you@example.com"
```
and make at least a few small commits of your own (for example your reflection, your screenshots, a README tweak) so the history shows your involvement.

The existing commits are authored as the placeholder "Project Author" and carry a `Co-Authored-By` line crediting Claude. To put **your** name on them (before the first push), run this once after the two `git config` lines above:
```bash
git rebase --root --exec 'git commit --amend --reset-author --no-edit'
```
Keep the `Co-Authored-By` trailer: it is an honest record that an AI assistant helped write the code, and many courses require you to disclose that. Check your programme's rules and be ready to explain every part of the code (that is what `docs/CODE_WALKTHROUGH.md` is for).
