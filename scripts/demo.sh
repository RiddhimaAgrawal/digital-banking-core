#!/usr/bin/env bash
# End-to-end demo of the banking core. Run it AFTER the app, Postgres, Kafka and Keycloak are up.
#   bash scripts/demo.sh
# Every step prints what it does and the HTTP status, so it is also a good script for the screen recording.
set -uo pipefail
API="${API_URL:-http://localhost:8080}/api/v1"
cd "$(dirname "$0")/.."

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

# call METHOD PATH TOKEN [JSON_BODY] [extra curl args...]  -> prints "HTTP <code>" then the body; body saved in $BODY
call() {
  local method="$1" path="$2" token="$3" body="${4:-}"; shift 4 2>/dev/null || shift $#
  local args=(-sS -o /tmp/demo_body.json -D /tmp/demo_headers.txt -w '%{http_code}' -X "$method" "$API$path" -H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  CODE=$(curl "${args[@]}" "$@")
  BODY=$(cat /tmp/demo_body.json)
  echo "HTTP $CODE  $(echo "$BODY" | head -c 400)"
  grep -i '^idempotent-replay' /tmp/demo_headers.txt | tr -d '\r' | sed 's/^/      header: /'  || true
}

say "0. Get tokens from Keycloak for alice and bob (customers), tina (teller), carl (compliance), admin1"
ALICE=$(scripts/get-token.sh alice); BOB=$(scripts/get-token.sh bob); TINA=$(scripts/get-token.sh tina)
CARL=$(scripts/get-token.sh carl);   ADMIN=$(scripts/get-token.sh admin1)
echo "tokens obtained (${#ALICE} chars each)"

say "1. Alice and Bob open their profiles (created automatically on first call)"
call GET /customers/me "$ALICE"; call GET /customers/me "$BOB"

say "2. Before KYC approval Alice cannot open an account  (expect 403 KYC_REQUIRED)"
call POST /accounts "$ALICE" '{"type":"CHECKING"}'

say "3. Alice and Bob submit KYC documents"
call POST /kyc "$ALICE" '{"documentType":"CITIZENSHIP","documentNumber":"27-01-76-12345","dateOfBirth":"1998-05-14","address":"Baneshwor, Kathmandu"}'
call POST /kyc "$BOB"   '{"documentType":"PASSPORT","documentNumber":"PA1234567","dateOfBirth":"1996-11-02","address":"Lakeside, Pokhara"}'

say "4. Carl (compliance) reviews the queue and approves both"
call GET "/admin/kyc?status=PENDING" "$CARL"
for id in $(echo "$BODY" | jget "' '.join(s['id'] for s in d)"); do
  call POST "/admin/kyc/$id/approve" "$CARL" '{"note":"documents verified"}'
done

say "5. Alice and Bob open checking accounts"
call POST /accounts "$ALICE" '{"type":"CHECKING"}'; A_ID=$(echo "$BODY" | jget "d['id']"); A_NO=$(echo "$BODY" | jget "d['accountNumber']")
call POST /accounts "$BOB"   '{"type":"CHECKING"}'; B_ID=$(echo "$BODY" | jget "d['id']"); B_NO=$(echo "$BODY" | jget "d['accountNumber']")
echo "Alice: $A_NO   Bob: $B_NO"

say "6. Tina (teller) deposits NPR 10,000 cash into Alice's account"
call POST "/accounts/$A_ID/deposit" "$TINA" '{"amount":10000.00,"description":"cash deposit"}' -H "Idempotency-Key: demo-deposit-$(date +%s)"

say "7. Alice sends NPR 2,500 to Bob   (expect 201)"
KEY="demo-transfer-$(date +%s)"
TRANSFER='{"sourceAccountNumber":"'"$A_NO"'","destinationAccountNumber":"'"$B_NO"'","amount":2500.00,"description":"rent share"}'
call POST /transfers "$ALICE" "$TRANSFER" -H "Idempotency-Key: $KEY"

say "8. RETRY the exact same request (network glitch!)  (expect 200 + Idempotent-Replay, NO second charge)"
call POST /transfers "$ALICE" "$TRANSFER" -H "Idempotency-Key: $KEY"
call GET "/accounts/$A_ID" "$ALICE"

say "9. Same key but a different amount is refused  (expect 422 IDEMPOTENCY_KEY_REUSED)"
call POST /transfers "$ALICE" "${TRANSFER/2500.00/9999.00}" -H "Idempotency-Key: $KEY"

say "10. Overdraft attempt  (expect 422 INSUFFICIENT_FUNDS)"
call POST /transfers "$ALICE" "${TRANSFER/2500.00/99999.00}" -H "Idempotency-Key: demo-big-$(date +%s)"

say "11. Fraud rule: a transfer above the NPR 500,000 limit  (expect 422 FRAUD_MAX_TRANSFER_AMOUNT)"
call POST /transfers "$ALICE" "${TRANSFER/2500.00/500000.01}" -H "Idempotency-Key: demo-fraud-$(date +%s)"

say "12. Fraud rule: velocity (max 5 transfers per 60 s). Fire 7 small transfers quickly  (the first 4 pass, then 429 FRAUD_VELOCITY)"
for i in 1 2 3 4 5 6 7; do
  call POST /transfers "$ALICE" "${TRANSFER/2500.00/1.00}" -H "Idempotency-Key: demo-burst-$i-$(date +%s%N)"
done

say "13. Compliance sees the fraud alerts that were recorded"
call GET /admin/fraud-alerts "$CARL"

say "14. Bob's ledger entries (every line is one side of a double-entry pair)"
call GET "/accounts/$B_ID/entries" "$BOB"

say "15. Monthly statement for the current month (JSON) and as CSV"
MONTH=$(date -u +%Y-%m)
call GET "/accounts/$A_ID/statements/$MONTH" "$ALICE"
curl -sS "$API/accounts/$A_ID/statements/$MONTH/csv" -H "Authorization: Bearer $ALICE" | head -20

say "16. Notifications created by the Kafka consumer (wait 2 s for the outbox publisher)"
sleep 2; call GET /notifications "$BOB"

say "17. Admin runs the reconciliation: the books must balance  (healthy=true, totalLedgerSum=0)"
call GET /admin/reconciliation "$ADMIN"

say "18. Security: a customer cannot use teller or admin endpoints  (expect 403)"
call POST "/accounts/$A_ID/deposit" "$ALICE" '{"amount":1.00}' -H "Idempotency-Key: nope-$(date +%s)"
call GET /admin/reconciliation "$ALICE"
echo; echo "Done. Swagger UI: ${API_URL:-http://localhost:8080}/swagger-ui.html"
