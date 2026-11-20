// Builds presentation/Digital_Banking_Core.pptx   ->   node presentation/build_deck.js
const path = require("path");
const pptxgen = require("pptxgenjs");
const SKILL = require("fs").readdirSync("/root/.claude/skills/synced").map(d => `/root/.claude/skills/synced/${d}/pptx/scripts/apply_theme.js`).find(p => require("fs").existsSync(p));
const { applyTheme } = require(SKILL);

const DOCS = path.join(__dirname, "..", "docs");
const OUT = path.join(__dirname, "Digital_Banking_Core.pptx");

const THEME = {
  name: "Ledger",
  headFontFace: "Cambria",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "10201F", lt1: "FFFFFF", dk2: "0F4C45", lt2: "EAF2F0",
    accent1: "0F766E", accent2: "D97706", accent3: "B91C1C",
    accent4: "15803D", accent5: "64748B", accent6: "1D4ED8",
    hlink: "0F766E", folHlink: "64748B",
  },
};

const pres = new pptxgen();
pres.layout = "LAYOUT_16x9"; // 10 x 5.625 in
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = "Digital Banking Core Platform";
pres.author = "Backend Development track";
const C = pres.SchemeColor;

// ---------- layouts ----------
pres.defineSlideMaster({
  title: "DARK",
  background: { color: C.text2 },
  slideNumber: { x: 9.2, y: 5.2, w: 0.5, h: 0.3, fontFace: "Calibri", fontSize: 10, color: C.background2 },
  objects: [],
});
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: C.background1 },
  margin: [0.5, 0.5, 0.5, 0.5],
  slideNumber: { x: 9.2, y: 5.2, w: 0.5, h: 0.3, fontFace: "Calibri", fontSize: 10, color: C.accent5 },
  objects: [
    {
      placeholder: {
        options: { name: "title", type: "title", x: 0.5, y: 0.3, w: 9, h: 0.8, fontFace: "Cambria", fontSize: 30, bold: true, color: C.text2, valign: "middle", margin: 0 },
        text: "",
      },
    },
  ],
});

const font = { fontFace: "Calibri" };
function content(title, section) {
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: section });
  s.addText(title, { placeholder: "title" });
  return s;
}
// signed-amount chip: a coloured circle with + or -
function chip(s, x, y, sign, name) {
  s.addShape(pres.ShapeType.ellipse, { x, y, w: 0.36, h: 0.36, fill: { color: sign === "-" ? C.accent3 : C.accent4 }, line: { type: "none" }, objectName: name });
  s.addText(sign === "-" ? "−" : "+", { x, y, w: 0.36, h: 0.36, align: "center", valign: "middle", fontSize: 16, bold: true, color: C.background1, margin: 0, isTextBox: true, ...font });
}
function card(s, x, y, w, h, name) {
  s.addShape(pres.ShapeType.roundRect, { x, y, w, h, rectRadius: 0.08, fill: { color: C.background2 }, line: { type: "none" }, objectName: name });
}

// ======================= 1. Title =======================
pres.addSection({ title: "Intro" });
{
  const s = pres.addSlide({ masterName: "DARK", sectionTitle: "Intro" });
  chip(s, 0.7, 0.8, "-", "debit chip");
  chip(s, 1.15, 0.8, "+", "credit chip");
  s.addText("= 0", { x: 1.6, y: 0.78, w: 1.2, h: 0.4, fontSize: 20, bold: true, color: C.accent2, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
  s.addText("Digital Banking Core Platform", { x: 0.7, y: 1.6, w: 8.6, h: 1.1, fontSize: 44, bold: true, color: C.background1, margin: 0, valign: "top", isTextBox: true, fontFace: "Cambria" });
  s.addText("Accounts, atomic transfers, a ledger that always balances, and identity checks", { x: 0.7, y: 2.85, w: 8, h: 0.8, fontSize: 20, color: C.background2, margin: 0, valign: "top", isTextBox: true, ...font });
  s.addText("Spring Boot  ·  PostgreSQL  ·  Flyway  ·  Kafka  ·  Keycloak  ·  OpenAPI", { x: 0.7, y: 4.6, w: 8.6, h: 0.4, fontSize: 14, color: C.accent2, margin: 0, isTextBox: true, ...font });
  s.addNotes("Introduce yourself and the problem in one sentence: a bank core must never create, lose or double-charge money. Everything in this talk is about how the design guarantees that. About 20 seconds.");
}

// ======================= 2. Problem =======================
pres.addSection({ title: "Problem" });
{
  const s = content("What a bank core must get right", "Problem");
  const rows = [
    ["Never lose or invent money", "Every transfer is a debit and a credit that cancel out"],
    ["Never charge twice", "A retry after a network failure must not move money again"],
    ["Stay correct under load", "50 requests on one account in the same millisecond"],
    ["Know who is who", "Login, roles, and KYC before a customer can move money"],
  ];
  rows.forEach((r, i) => {
    const y = 1.35 + i * 0.95;
    card(s, 0.5, y, 9, 0.8, `problem card ${i + 1}`);
    s.addShape(pres.ShapeType.ellipse, { x: 0.7, y: y + 0.17, w: 0.46, h: 0.46, fill: { color: C.accent1 }, line: { type: "none" }, objectName: `problem number ${i + 1}` });
    s.addText(String(i + 1), { x: 0.7, y: y + 0.17, w: 0.46, h: 0.46, align: "center", valign: "middle", fontSize: 16, bold: true, color: C.background1, margin: 0, isTextBox: true, ...font });
    s.addText(r[0], { x: 1.4, y: y + 0.06, w: 7.9, h: 0.38, fontSize: 18, bold: true, color: C.text2, margin: 0, valign: "middle", isTextBox: true, ...font });
    s.addText(r[1], { x: 1.4, y: y + 0.42, w: 7.9, h: 0.3, fontSize: 14, color: C.text1, margin: 0, valign: "middle", isTextBox: true, ...font });
  });
  s.addNotes("Frame the four guarantees. The rest of the talk maps one design decision to each guarantee.");
}

// ======================= 3. Double entry =======================
pres.addSection({ title: "Design" });
{
  const s = content("Double-entry: every transfer sums to zero", "Design");
  const hdr = (t) => ({ text: t, options: { bold: true, color: C.background1, fill: { color: C.text2 }, ...font, fontSize: 14 } });
  const cell = (t, o = {}) => ({ text: t, options: { ...font, fontSize: 14, color: C.text1, ...o } });
  s.addTable([
    [hdr("Entry"), hdr("Account"), hdr("Amount"), hdr("Balance after")],
    [cell("Debit"), cell("Alice"), cell("−2,500.00", { bold: true, color: C.accent3 }), cell("7,500.00")],
    [cell("Credit"), cell("Bob"), cell("+2,500.00", { bold: true, color: C.accent4 }), cell("2,500.00")],
    [cell("Sum", { bold: true }), cell(""), cell("0.00", { bold: true }), cell("")],
  ], { x: 0.5, y: 1.4, w: 5.6, colW: [1.1, 1.3, 1.6, 1.6], rowH: 0.5, border: { type: "solid", color: "CBD5D1", pt: 1 }, objectName: "double entry table" });
  s.addText("Enforced three ways", { x: 6.5, y: 1.35, w: 3, h: 0.4, fontSize: 18, bold: true, color: C.text2, margin: 0, isTextBox: true, fontFace: "Cambria" });
  s.addText([
    { text: "LedgerService is the only code that writes entries", options: { bullet: true, breakLine: true } },
    { text: "A deferred database trigger rejects any unbalanced transaction at COMMIT", options: { bullet: true, breakLine: true } },
    { text: "Ledger is append-only: no UPDATE, no DELETE", options: { bullet: true } },
  ], { x: 6.5, y: 1.8, w: 3, h: 2.3, fontSize: 14, color: C.text1, paraSpaceAfter: 8, valign: "top", margin: 0, isTextBox: true, ...font });
  card(s, 0.5, 3.7, 5.6, 1.3, "sum zero callout");
  s.addText("Σ of all ledger entries = 0.00", { x: 0.7, y: 3.78, w: 5.2, h: 0.55, fontSize: 20, bold: true, color: C.accent1, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
  s.addText("Cash deposits balance too: the bank's own cash account is the other side.", { x: 0.7, y: 4.35, w: 5.2, h: 0.6, fontSize: 14, color: C.text1, margin: 0, valign: "top", isTextBox: true, ...font });
  s.addNotes("Walk the table. Key point: even a bug in the Java code cannot unbalance the books, because PostgreSQL refuses it at commit. This was tested against a real PostgreSQL 16.");
}

// ======================= 4. Architecture =======================
{
  const s = content("Architecture", "Design");
  const box = (x, y, w, h, fill, title, sub, name) => {
    s.addShape(pres.ShapeType.roundRect, { x, y, w, h, rectRadius: 0.08, fill: { color: fill }, line: { type: "none" }, objectName: name });
    s.addText(title, { x: x + 0.15, y: y + 0.08, w: w - 0.3, h: 0.4, fontSize: 16, bold: true, color: C.background1, margin: 0, valign: "middle", isTextBox: true, ...font });
    if (sub) s.addText(sub, { x: x + 0.15, y: y + 0.5, w: w - 0.3, h: h - 0.6, fontSize: 12, color: C.background1, margin: 0, valign: "top", isTextBox: true, ...font });
  };
  const arrow = (x1, y1, x2, y2, name) => s.addShape(pres.ShapeType.line, { x: x1, y: y1, w: x2 - x1, h: y2 - y1, line: { color: C.text2, width: 2, endArrowType: "triangle" }, objectName: name });
  box(0.5, 1.4, 2.1, 1.35, C.accent5, "Clients", "Swagger UI, curl, teller and compliance tools", "clients box");
  box(0.5, 3.45, 2.1, 1.35, C.accent6, "Keycloak", "Logs users in, issues signed JWTs, holds the roles", "keycloak box");
  arrow(1.55, 2.75, 1.55, 3.45, "login arrow");
  s.addText("login", { x: 1.7, y: 2.9, w: 0.9, h: 0.3, fontSize: 12, color: C.text1, margin: 0, isTextBox: true, ...font });
  // app
  s.addShape(pres.ShapeType.roundRect, { x: 3.1, y: 1.3, w: 3.7, h: 3.6, rectRadius: 0.08, fill: { color: C.text2 }, line: { type: "none" }, objectName: "app box" });
  s.addText("Spring Boot application", { x: 3.25, y: 1.36, w: 3.4, h: 0.4, fontSize: 16, bold: true, color: C.background1, margin: 0, valign: "middle", isTextBox: true, ...font });
  const rows = ["Security filter: verifies the JWT", "REST controllers: check roles", "Services: transfer, ledger, fraud, KYC", "JDBC repositories: explicit SQL", "Outbox publisher (every 500 ms)", "Notification consumer"];
  rows.forEach((r, i) => {
    const y = 1.85 + i * 0.5;
    s.addShape(pres.ShapeType.roundRect, { x: 3.25, y, w: 3.4, h: 0.42, rectRadius: 0.05, fill: { color: C.background1 }, line: { type: "none" }, objectName: `app layer ${i + 1}` });
    s.addText(r, { x: 3.35, y, w: 3.2, h: 0.42, fontSize: 12, color: C.text1, margin: 0, valign: "middle", isTextBox: true, ...font });
  });
  box(7.3, 1.4, 2.2, 1.55, C.accent1, "PostgreSQL 16", "Accounts, ledger, idempotency keys, outbox. Schema by Flyway", "postgres box");
  box(7.3, 3.35, 2.2, 1.45, C.accent2, "Kafka", "Two topics: transactions completed, and KYC status changed", "kafka box");
  arrow(2.6, 2.1, 3.1, 2.1, "clients to app arrow");
  arrow(2.6, 4.1, 3.1, 4.1, "keycloak to app arrow");
  arrow(6.8, 2.1, 7.3, 2.1, "app to postgres arrow");
  arrow(6.8, 4.05, 7.3, 4.05, "app to kafka arrow");
  s.addNotes("Client logs in at Keycloak and calls the Spring Boot API with the token. The app verifies the token with Keycloak's public keys. Everything that must be correct lives in PostgreSQL. Kafka is reached only through the outbox, so Kafka being down never blocks a transfer. The detailed diagram is docs/architecture.png.");
}

// ======================= 5. Data model =======================
{
  const s = content("Data model (Flyway V1–V4)", "Design");
  const table = (x, y, name, cols, id) => {
    s.addShape(pres.ShapeType.roundRect, { x, y, w: 2.7, h: 1.65, rectRadius: 0.06, fill: { color: C.background2 }, line: { type: "none" }, objectName: `table ${id}` });
    s.addShape(pres.ShapeType.rect, { x, y, w: 2.7, h: 0.4, fill: { color: C.text2 }, line: { type: "none" }, objectName: `table header ${id}` });
    s.addText(name, { x: x + 0.12, y, w: 2.5, h: 0.4, fontSize: 14, bold: true, color: C.background1, margin: 0, valign: "middle", isTextBox: true, ...font });
    s.addText(cols.map((c, k) => ({ text: c, options: { breakLine: k < cols.length - 1 } })), { x: x + 0.12, y: y + 0.45, w: 2.5, h: 1.15, fontSize: 12, color: C.text1, margin: 0, valign: "top", paraSpaceAfter: 2, isTextBox: true, ...font });
  };
  const arrow = (x1, y1, x2, y2, name) => s.addShape(pres.ShapeType.line, { x: x1, y: y1, w: x2 - x1, h: y2 - y1, line: { color: C.accent2, width: 2, endArrowType: "triangle" }, objectName: name });
  table(0.5, 1.3, "customers", ["id, keycloak_user_id", "full_name, email", "kyc_status"], "customers");
  table(3.6, 1.3, "accounts", ["id, customer_id", "account_number, currency", "balance (cached)", "allow_negative, status"], "accounts");
  table(0.5, 3.2, "transactions", ["id, type, status", "source_account_id", "destination_account_id", "amount, created_at"], "transactions");
  table(3.6, 3.2, "ledger_entries", ["transaction_id, account_id", "amount (− debit, + credit)", "balance_after", "append-only"], "ledger");
  table(6.9, 3.2, "idempotency_keys", ["caller + idem_key (primary key)", "request_hash", "transaction_id"], "idem");
  arrow(3.2, 2.1, 3.6, 2.1, "customer to account arrow");
  arrow(3.2, 4.0, 3.6, 4.0, "transaction to ledger arrow");
  arrow(4.95, 2.95, 4.95, 3.2, "account to ledger arrow");
  arrow(6.9, 4.0, 6.3, 4.0, "idempotency link arrow");
  s.addText([
    { text: "Money is NUMERIC(19,2)", options: { bullet: true, breakLine: true } },
    { text: "Ledger is the truth; balance is a cache", options: { bullet: true, breakLine: true } },
    { text: "CHECK: balance never below zero", options: { bullet: true } },
  ], { x: 6.9, y: 1.3, w: 2.7, h: 1.65, fontSize: 14, color: C.text1, paraSpaceAfter: 6, valign: "top", margin: 0, isTextBox: true, ...font });
  s.addText("Also: kyc_submissions, fraud_alerts, outbox_events, notifications, account_statements", { x: 0.5, y: 4.95, w: 8.6, h: 0.3, fontSize: 12, color: C.accent5, margin: 0, isTextBox: true, ...font });
  s.addNotes("Flyway applies V1 to V4 in order, once each, so schema changes are version controlled like code. Full diagram with every column: docs/er-diagram.png.");
}

// ======================= 6. Life of a transfer =======================
{
  const s = content("The life of one transfer", "Design");
  const steps = [
    "Reserve the Idempotency-Key, or replay the old result",
    "Authorise: caller owns the source, KYC approved",
    "Lock both accounts in ascending id order",
    "Fraud rules: amount, daily limit, velocity",
    "Check funds; write transaction, debit and credit",
    "Update both account balances",
    "Write the Kafka event to the outbox table",
    "Store the result on the key and COMMIT",
  ];
  steps.forEach((t, i) => {
    const col = Math.floor(i / 4), row = i % 4;
    const x = 0.5 + col * 4.6, y = 1.3 + row * 0.9;
    card(s, x, y, 4.4, 0.78, `transfer step card ${i + 1}`);
    s.addShape(pres.ShapeType.ellipse, { x: x + 0.15, y: y + 0.15, w: 0.48, h: 0.48, fill: { color: C.accent1 }, line: { type: "none" }, objectName: `transfer step number ${i + 1}` });
    s.addText(String(i + 1), { x: x + 0.15, y: y + 0.15, w: 0.48, h: 0.48, align: "center", valign: "middle", fontSize: 16, bold: true, color: C.background1, margin: 0, isTextBox: true, ...font });
    s.addText(t, { x: x + 0.8, y, w: 3.5, h: 0.78, fontSize: 14, color: C.text1, margin: 0, valign: "middle", isTextBox: true, ...font });
  });
  s.addText("One database transaction: all of it commits, or none of it does", { x: 0.5, y: 4.95, w: 8.6, h: 0.35, fontSize: 14, bold: true, italic: true, color: C.accent1, margin: 0, valign: "middle", isTextBox: true, ...font });
  s.addNotes("Any exception rolls back everything, including the idempotency key, so the client can retry. A fraud block also rolls back, and only then is the fraud alert saved. At COMMIT the database trigger checks that the entries sum to zero. Sequence diagram: docs/transfer-sequence.png.");
}

// ======================= 7. Concurrency =======================
pres.addSection({ title: "Challenges" });
{
  const s = content("Challenge 1: many requests at once", "Challenges");
  card(s, 0.5, 1.35, 4.35, 1.95, "lost update card");
  s.addText("Lost update", { x: 0.7, y: 1.45, w: 4, h: 0.4, fontSize: 18, bold: true, color: C.text2, margin: 0, isTextBox: true, fontFace: "Cambria" });
  s.addText("Two transfers read balance 1,000 and each spend 600. Without a lock both succeed.\nFix: SELECT … FOR NO KEY UPDATE on both accounts.", { x: 0.7, y: 1.9, w: 4, h: 1.5, fontSize: 14, color: C.text1, margin: 0, valign: "top", isTextBox: true, ...font });
  card(s, 5.15, 1.35, 4.35, 1.95, "deadlock card");
  s.addText("Deadlock", { x: 5.35, y: 1.45, w: 4, h: 0.4, fontSize: 18, bold: true, color: C.text2, margin: 0, isTextBox: true, fontFace: "Cambria" });
  s.addText("A→B and B→A each hold one lock and wait for the other.\nFix: always lock in ascending account id, so one simply waits.", { x: 5.35, y: 1.9, w: 4, h: 1.5, fontSize: 14, color: C.text1, margin: 0, valign: "top", isTextBox: true, ...font });
  s.addText("10", { x: 0.5, y: 3.75, w: 1.3, h: 1.0, fontSize: 60, bold: true, color: C.accent1, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
  s.addText("of 30 parallel transfers of 100 from a 1,000 balance succeed; the other 20 get INSUFFICIENT_FUNDS. Balance ends at exactly 0.", { x: 1.9, y: 3.75, w: 4.3, h: 1.0, fontSize: 14, color: C.text1, margin: 0, valign: "middle", isTextBox: true, ...font });
  s.addText("0", { x: 6.4, y: 3.75, w: 0.9, h: 1.0, fontSize: 60, bold: true, color: C.accent1, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
  s.addText("deadlocks in 40 simultaneous opposite transfers", { x: 7.4, y: 3.75, w: 2.1, h: 1.0, fontSize: 14, color: C.text1, margin: 0, valign: "middle", isTextBox: true, ...font });
  s.addNotes("These numbers come from verification/concurrency_check.py, which replays the exact SQL of a transfer from parallel connections against a real PostgreSQL 16. The Java version of the same scenarios is in TransferConcurrencyTest.");
}

// ======================= 8. Idempotency =======================
{
  const s = content("Challenge 2: no double-charging", "Challenges");
  const steps = [
    ["Client sends", "same Idempotency-Key on every retry"],
    ["Key row is inserted", "in the same transaction as the money"],
    ["Retry arrives", "key exists, so return the original result"],
  ];
  steps.forEach((st, i) => {
    const x = 0.5 + i * 3.05;
    card(s, x, 1.4, 2.85, 1.6, `idempotency step ${i + 1}`);
    s.addText(String(i + 1), { x: x + 0.2, y: 1.5, w: 0.5, h: 0.5, fontSize: 28, bold: true, color: C.accent2, margin: 0, isTextBox: true, fontFace: "Cambria" });
    s.addText(st[0], { x: x + 0.2, y: 2.0, w: 2.5, h: 0.4, fontSize: 16, bold: true, color: C.text2, margin: 0, isTextBox: true, ...font });
    s.addText(st[1], { x: x + 0.2, y: 2.4, w: 2.5, h: 0.55, fontSize: 14, color: C.text1, margin: 0, valign: "top", isTextBox: true, ...font });
  });
  s.addText("20 → 1", { x: 0.5, y: 3.3, w: 2.6, h: 1.1, fontSize: 54, bold: true, color: C.accent1, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
  s.addText([
    { text: "20 simultaneous identical requests: 1 transfer, 19 replays, debited once", options: { bullet: true, breakLine: true } },
    { text: "Same key with a different amount: 422 IDEMPOTENCY_KEY_REUSED", options: { bullet: true, breakLine: true } },
    { text: "A failed request leaves no key, so it can be retried", options: { bullet: true } },
  ], { x: 3.3, y: 3.3, w: 6.2, h: 1.5, fontSize: 14, color: C.text1, paraSpaceAfter: 6, valign: "middle", margin: 0, isTextBox: true, ...font });
  s.addNotes("The key is part of the same database transaction as the debit and credit, so the key exists if and only if the transfer committed. Concurrent duplicates wait on the unique key and then return the winner's result.");
}

// ======================= 9. Outbox =======================
{
  const s = content("Challenge 3: events without loss", "Challenges");
  const boxes = [
    ["Transfer commits", "debit, credit and an outbox row, in one transaction", C.accent1],
    ["Outbox publisher", "every 500 ms sends unpublished rows to Kafka and waits for the ack", C.accent6],
    ["Kafka consumer", "stores a notification; duplicates ignored by event id", C.accent4],
  ];
  boxes.forEach((b, i) => {
    const x = 0.5 + i * 3.2;
    s.addShape(pres.ShapeType.roundRect, { x, y: 1.5, w: 2.7, h: 1.6, rectRadius: 0.08, fill: { color: b[2] }, line: { type: "none" }, objectName: `outbox box ${i + 1}` });
    s.addText(b[0], { x: x + 0.15, y: 1.6, w: 2.4, h: 0.45, fontSize: 18, bold: true, color: C.background1, margin: 0, valign: "middle", isTextBox: true, ...font });
    s.addText(b[1], { x: x + 0.15, y: 2.1, w: 2.4, h: 1.2, fontSize: 14, color: C.background1, margin: 0, valign: "top", isTextBox: true, ...font });
    if (i < 2) s.addText("→", { x: x + 2.7, y: 2.1, w: 0.5, h: 0.5, fontSize: 28, bold: true, color: C.text2, align: "center", valign: "middle", margin: 0, isTextBox: true, ...font });
  });
  s.addText([
    { text: "Never an event for a rolled-back transfer, never a lost event for a committed one", options: { bullet: true, breakLine: true } },
    { text: "Kafka down: transfers still succeed; events wait in the table", options: { bullet: true, breakLine: true } },
    { text: "Delivery is at-least-once, so consumers de-duplicate", options: { bullet: true } },
  ], { x: 0.5, y: 3.4, w: 9, h: 1.3, fontSize: 14, color: C.text1, paraSpaceAfter: 6, valign: "top", margin: 0, isTextBox: true, ...font });
  s.addNotes("The database and Kafka cannot share one transaction, so we write the event to a table inside the money transaction and ship it afterwards. This is the transactional outbox pattern.");
}

// ======================= 10. Identity & safety =======================
pres.addSection({ title: "Safety" });
{
  const s = content("Identity and safety rules", "Safety");
  const cols = [
    ["Keycloak (OAuth2)", ["App never sees passwords", "JWT checked on every call", "Roles: customer, teller, compliance, admin", "Customers see only their own accounts"]],
    ["KYC workflow", ["Submit → review → approve or reject", "No account or transfer until approved", "Four-eyes: no one reviews their own KYC", "Under-18 refused"]],
    ["Fraud rules", ["Max single transfer: 500,000", "Daily limit: 1,000,000", "Velocity: max 5 per minute", "Blocks logged for compliance"]],
  ];
  cols.forEach((c, i) => {
    const x = 0.5 + i * 3.05;
    card(s, x, 1.35, 2.85, 3.5, `safety card ${i + 1}`);
    s.addText(c[0], { x: x + 0.2, y: 1.45, w: 2.5, h: 0.45, fontSize: 18, bold: true, color: C.text2, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
    s.addText(c[1].map((t, k) => ({ text: t, options: { bullet: true, breakLine: k < c[1].length - 1 } })), { x: x + 0.2, y: 2.0, w: 2.5, h: 2.7, fontSize: 14, color: C.text1, paraSpaceAfter: 8, valign: "top", margin: 0, isTextBox: true, ...font });
  });
  s.addNotes("Fraud rules run while the source account is locked, so two parallel requests cannot both slip under a limit they would jointly break. Limits are configuration in application.yml, not code.");
}

// ======================= 11. Results =======================
pres.addSection({ title: "Results" });
{
  const s = content("Results", "Results");
  const stats = [
    ["9 / 9", "database-level concurrency checks passed on PostgreSQL 16"],
    ["32", "Java integration tests written (Testcontainers, real PostgreSQL)"],
    ["Σ = 0", "final reconciliation: ledger sum 0, no unbalanced transaction, no balance mismatch"],
  ];
  stats.forEach((st, i) => {
    const x = 0.5 + i * 3.05;
    card(s, x, 1.35, 2.85, 1.75, `result stat ${i + 1}`);
    s.addText(st[0], { x: x + 0.15, y: 1.4, w: 2.55, h: 0.8, fontSize: 36, bold: true, color: C.accent1, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
    s.addText(st[1], { x: x + 0.15, y: 2.2, w: 2.55, h: 0.85, fontSize: 12, color: C.text1, margin: 0, valign: "top", isTextBox: true, ...font });
  });
  s.addChart(pres.charts.BAR, [{ name: "Latency (ms)", labels: ["p50", "p95", "p99"], values: [68.4, 97.8, 113.7] }], {
    x: 0.5, y: 3.25, w: 4.4, h: 1.9, barDir: "col", chartColors: ["0F766E"],
    showTitle: true, title: "Latency per transfer (ms)", titleFontSize: 12, titleColor: "10201F", titleFontFace: "+mn-lt",
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 11, dataLabelFontFace: "+mn-lt", dataLabelColor: "10201F",
    catAxisLabelFontSize: 11, catAxisLabelFontFace: "+mn-lt", catAxisLabelColor: "10201F",
    valAxisHidden: true, valGridLine: { style: "none" }, catGridLine: { style: "none" }, showLegend: false,
  });
  s.addText("228 transfers/s", { x: 5.2, y: 3.3, w: 4.3, h: 0.6, fontSize: 28, bold: true, color: C.accent2, margin: 0, valign: "middle", isTextBox: true, fontFace: "Cambria" });
  s.addText("3,000 transfers, 16 threads, 40 account pairs. Measured on a small shared sandbox with a Python replay of the transfer SQL, so it shows the database design behaves. It is not a benchmark of the Spring application.", { x: 5.2, y: 3.95, w: 4.3, h: 1.2, fontSize: 12, color: C.text1, margin: 0, valign: "top", isTextBox: true, ...font });
  s.addNotes("Be honest about scope: the throughput and latency numbers are from the database-level replay, not the Spring app. Before presenting, run mvn test on your laptop and add a screenshot of 'Tests run: 32, Failures: 0'. Optionally measure your own app with k6 (see docs/SETUP_GUIDE.md section 7) and replace the chart values.");
}

// ======================= 12. Next steps =======================
{
  const s = content("Limitations and next steps", "Results");
  const items = [
    ["Single hot cash account", "Shard clearing accounts per branch"],
    ["One currency (NPR)", "FX rates and a conversion ledger account"],
    ["No reversals yet", "Correcting entries with the REVERSED status"],
    ["Static fraud thresholds", "Train a scoring model on PaySim or IBM AML data"],
    ["Demo-grade Keycloak", "Authorization code + PKCE, real secrets, TLS"],
    ["Spring Boot 3.5", "Upgrade to Boot 4.x (3.5 open-source support ended Jun 2026)"],
  ];
  items.forEach((it, i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = 0.5 + col * 4.6, y = 1.35 + row * 1.2;
    card(s, x, y, 4.4, 1.05, `next step card ${i + 1}`);
    s.addText(it[0], { x: x + 0.2, y: y + 0.08, w: 4.0, h: 0.4, fontSize: 16, bold: true, color: C.text2, margin: 0, valign: "middle", isTextBox: true, ...font });
    s.addText(it[1], { x: x + 0.2, y: y + 0.5, w: 4.0, h: 0.45, fontSize: 14, color: C.text1, margin: 0, valign: "top", isTextBox: true, ...font });
  });
  s.addNotes("Close with what you would do next. Then take questions. Likely questions are listed in docs/CODE_WALKTHROUGH.md section 16.");
}

(async () => {
  await pres.writeFile({ fileName: OUT });
  await applyTheme(OUT, THEME);
  console.log("wrote", OUT);
})();
