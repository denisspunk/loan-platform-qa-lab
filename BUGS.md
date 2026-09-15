# Findings: loan service exploratory session

Defects, risks and open questions found while reading the service and poking it live.
Not to be confused with `starter/src/main/java/lab/loans/Bugs.java`: those are the lab's seeded bugs, switched on with `-Dlab.bugs=...`. Everything below is present with **no** seeded bug enabled.

- **Date:** 2026-09-15
- **Build:** `starter/`, JDK 26.0.2.1, service run locally on `http://127.0.0.1:8080` with `LoggingDeviceLockClient` (partner calls are printed, not sent)
- **Evidence:** *reproduced live*: request and response seen · *observed in log*: partner call printed by the service · *code reading*: not reproduced yet

| ID | Finding | Severity | Evidence | Status |
|---|---|---|---|---|
| F-01 | Payment for a paid-off loan is accepted and silently dropped | High | reproduced live | Partly addressed in `starter` (a344ffb): recorded in Postgres; money handling still needs product decision |
| F-02 | Overpayment on the final payment is not tracked | High | reproduced live | Open: needs product decision |
| F-03 | Scheduled relock is not cancelled when the loan is paid off | Critical, if the partner does not cancel it | observed in log | Open: question to partner contract |
| F-04 | Partner failure between unlock and relock leaves the phone unlocked for good | High | code reading | Open: reproduce with KnoxStub |
| F-05 | Duplicate protection lives in memory and is lost on restart | Medium | code reading | Fixed in `starter` (a344ffb) when running with Postgres; verified locally and on Render |
| F-06 | Unlock is sent again for a phone that is already unlocked | Low | observed in log | Open: question to partner contract |
| F-07 | `relockAt` is sent with microseconds; the contract example has whole seconds | Low | observed in log | Open: question to partner contract |
| F-08 | `GET /loans/` with an empty id returns 404 instead of 400 | Low | reproduced live | Open |
| F-09 | `receivedAt` is ignored; unlock time counts from processing, not from payment | Low | code reading | Open: question to product |
| F-10 | A path outside the API gets an HTML 404 page instead of a JSON error | Low | reproduced live | Open |
| F-11 | A missing or misspelled field is reported as an invalid value; unknown fields are silently ignored | Low | reproduced live | Open: question to API contract |
| F-12 | Error responses expose internal JSON parser messages | Low | reproduced live | Open |

---

## F-01 · Payment for a paid-off loan is accepted and silently dropped

**Severity:** High: real customer money disappears, and the API tells the payer everything is fine.

**Steps**
1. Loan `LN-b000a5a5`: price 300, dailyRate 100, paid off by `MPESA-1` 150 + `MPESA-2` 50 + `MPESA-3` 100 → `PAID_OFF`, `RELEASED`.
2. `POST /payments {"paymentId":"MPESA-4","loanId":"LN-b000a5a5","amount":1000}`

**Actual**
- `202 ACCEPTED`.
- Loan unchanged: `paid 300`, `balance 0`, `credit 0`.
- No partner call, no dead letter, no log line, no alert. The 1000 KES exist nowhere in the system.

**Expected:** to be decided by product: refund, keep as customer balance, reject, or at least put the payment on a manual-review queue and alert ops.

**Why:** `PaymentProcessor` records the id and returns `LOAN_ALREADY_PAID_OFF`, but `handle()` discards the result. Nothing else in the service reads `ProcessingResult`. The branch is deliberate, so this is a requirements gap rather than a coding slip, but it still needs a ticket. (Line references in the original report pointed to the in-memory version of the processor.)

**Update 2026-09-15, commit a344ffb:** with `DATABASE_URL` set, the payment is no longer lost without a trace. It is stored in `processed_payments` with its amount and result. Verified locally against Postgres in Docker:
```
 payment_id | amount | result
------------+--------+-----------------------
 MPESA-3    |   1000 | LOAN_ALREADY_PAID_OFF
```
Still open: the API answers `202`, nobody is alerted, and what happens to the money (refund, balance, manual review) is a product decision.

---

## F-02 · Overpayment on the final payment is not tracked

**Severity:** High: same class as F-01, the excess is lost silently.

**Steps**
1. `POST /loans {"deviceId":"350000000000002","price":300,"dailyRate":100}` → `LN-e2d78ecc`
2. `POST /payments {"paymentId":"CLAUDE-OVERPAY-1","loanId":"LN-e2d78ecc","amount":350}`

**Actual:** `202`; loan `paid 350`, `balance 0`, `credit 0`, `PAID_OFF`, `RELEASED`; partner gets `release`. The extra 50 KES are not shown as balance, credit or refund.

**Expected:** to be decided by product, same options as F-01.

**Why:** [Loan.java:69](starter/src/main/java/lab/loans/domain/Loan.java#L69) reports `balance` as `Math.max(0, price - paid)`, so a negative balance is clamped to 0; the release decision sets credit to 0.

---

## F-03 · Scheduled relock is not cancelled when the loan is paid off

**Severity:** Critical if confirmed: a customer who paid in full gets their phone locked.

**Steps:** on loan `LN-b000a5a5` (see F-01), partner calls in order:
```
[knox] relock  350000000000001 at 2026-09-17T08:54:19.244206Z (correlation MPESA-2)
[knox] release 350000000000001 (correlation MPESA-3)
```

**Actual:** the service sends `release` and never cancels the relock it scheduled for 2026-09-17.

**Expected:** the phone stays unlocked after payoff. Either `release` is documented to cancel pending relocks, or the service cancels them explicitly.

**Why:** [PaymentProcessor.java:62](starter/src/main/java/lab/loans/service/PaymentProcessor.java#L62) calls only `release`. Whether that is enough depends on partner behaviour that the service code cannot show.

**Next:** ask the partner contract owner; e2e case: pay off during an unlock window, advance `TestClock` past `unlockedUntil`, expect `RELEASED`.

---

## F-04 · Partner failure between unlock and relock leaves the phone unlocked for good

**Severity:** High: free unlocked phone, and our records disagree with the partner.

**Scenario (code reading, not reproduced)**
1. Payment arrives, `unlock` succeeds at the partner ([line 59](starter/src/main/java/lab/loans/service/PaymentProcessor.java#L59)).
2. `scheduleRelock` fails, e.g. partner returns 500 → `DeviceLockException`, a `RuntimeException` ([line 60](starter/src/main/java/lab/loans/service/PaymentProcessor.java#L60)).
3. `loan.apply` (line 68) and recording the id (line 69) are skipped.
4. The bus retries 3 times ([InMemoryEventBus.java:47-55](starter/src/main/java/lab/loans/events/InMemoryEventBus.java#L47-L55)): `unlock` is sent again each time, relock keeps failing → dead letter.

**Actual (expected by reading):** at the partner, the phone is unlocked with no relock scheduled. In our service, the loan is still `LOCKED` and the payment is not applied.

**Expected:** no unlock without a scheduled relock: schedule the relock first, or compensate with a lock when relock fails.

**Next:** reproduce in lessons 5 and 7 with `KnoxStub` answering 500 on `/relock`.

---

## F-05 · Duplicate protection lives in memory and is lost on restart

**Severity:** Medium (risk).

**Why:** processed ids are a `Set` in memory ([PaymentProcessor.java:28](starter/src/main/java/lab/loans/service/PaymentProcessor.java#L28)). Kafka redelivers events after a consumer restart, so a payment processed just before a restart can be applied twice.

**Note:** cannot be reproduced in the lab, because loans are in memory too and vanish on restart. In production, store processed ids next to the loan, with a unique constraint on `paymentId`.

**Fixed 2026-09-15 in `starter`, commit a344ffb**, when the service runs with `DATABASE_URL`: processed payments live in the Postgres table `processed_payments` with `payment_id` as the primary key, and the loan update and the payment record are written in one transaction. Without `DATABASE_URL` the service still keeps them in memory, as before.

Verified twice:
- **Locally, Postgres in Docker:** pay `MPESA-1` 150 → stop and start the service → the loan still shows `paid 150` → redeliver `MPESA-1`, then pay `MPESA-2` 150. The table holds one `MPESA-1` row and `MPESA-2` as `APPLIED`, so the redelivery was ignored.
- **On Render, 12:24 UTC:** pay `RENDER-MPESA-1` 150 → restart the service through the Render API (new start logged at 12:24:34, `with Postgres storage`) → the loan still shows `paid 150` → redeliver `RENDER-MPESA-1`, then pay `RENDER-MPESA-2` 150. The partner log shows `release ... (correlation RENDER-MPESA-2)`: had the redelivery been applied, the release would have carried `RENDER-MPESA-1`.

Not covered: two service instances processing the same payment at the same moment. The check and the insert are separate statements, so this needs a row lock or relying on the primary key conflict once the service scales out.

---

## F-06 · Unlock is sent again for a phone that is already unlocked

**Severity:** Low / contract question.

**Observed:** `MPESA-2` arrived while the phone was unlocked until 2026-09-16, and the service sent `unlock` again before extending the relock to 2026-09-17.

**Question:** does the partner accept a repeated `unlock` safely? It also happens on every retry in F-04.

---

## F-07 · `relockAt` is sent with microseconds; the contract example has whole seconds

**Severity:** Low / contract question.

**Observed:** `relock ... at 2026-09-16T08:54:19.244206Z`. The partner contract in [HttpDeviceLockClient.java](starter/src/main/java/lab/loans/knox/HttpDeviceLockClient.java) shows `"relockAt": "2026-09-16T09:00:00Z"`. The HTTP client sends `relockAt.toString()` ([line 40](starter/src/main/java/lab/loans/knox/HttpDeviceLockClient.java#L40)), which keeps the fraction.

**Question:** does the real partner parse fractional seconds? A contract test should pin the exact format.

---

## F-08 · `GET /loans/` with an empty id returns 404 instead of 400

**Severity:** Low.

**Steps:** `GET http://127.0.0.1:8080/loans/`

**Actual:** `404 {"error":"loan  not found"}` (empty id in the message).

**Expected:** `400`, "loan id is required", or `404 no route`.

**Why:** [HttpApi.java:103-109](starter/src/main/java/lab/loans/api/HttpApi.java#L103-L109) takes everything after `/loans/` as the id, including an empty string.

---

## F-09 · `receivedAt` is ignored; unlock time counts from processing, not from payment

**Severity:** Low / product question.

**Why:** `PaymentReceived.receivedAt` is set by the API but never read. The unlock window starts at `clock.instant()` when the processor runs ([PaymentProcessor.java:56](starter/src/main/java/lab/loans/service/PaymentProcessor.java#L56)).

**Question:** if an event is delayed by an hour (consumer lag, retries), should the customer lose that hour of paid time?

---

## F-10 · A path outside the API gets an HTML 404 page instead of a JSON error

**Severity:** Low: a client that always parses JSON breaks on this response.

**Steps (2026-09-15, local run):** `GET /foo`, `GET /`

**Actual:** `404`, `Content-Type: text/html`, body `<h1>404 Not Found</h1>No context found for request`.

**Expected:** the same shape as every other error of the service: `404`, `application/json`, `{"error": "no route for GET /foo"}`. Wrong methods on known paths already answer that way, e.g. `GET /payments` → `{"error":"no route for GET /payments"}`.

**Why:** the JDK `HttpServer` only has contexts for `/loans`, `/payments` and `/health` ([HttpApi.java](starter/src/main/java/lab/loans/api/HttpApi.java)); any other path is answered by the server itself, not by the service code.

**Test:** `HealthAndRoutingTest.pathOutsideTheApiIsRefusedWithAJsonError`, disabled until fixed.

---

## F-11 · A missing or misspelled field is reported as an invalid value; unknown fields are silently ignored

**Severity:** Low / API contract question.

**Steps (2026-09-15, local run)**
1. `POST /loans {"deviceId":"IMEI","dailyRate":100}` (no `price`) → `400 {"error":"price must be positive"}`
2. `POST /loans {"deviceId":"1","price":300,"dailyRate":100,"extra":"ignored"}` → `201`

**Actual:** a missing number becomes `0` on deserialization, so the client is told the value is wrong rather than absent. A typo such as `"dailyrate"` is dropped as an unknown field, and the client gets `dailyRate must be positive`.

**Expected:** to be agreed in the API contract: either `"price is required"` for a missing field, or rejecting unknown fields so typos surface immediately.

**Why:** `HttpApi` configures Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = false` and reads amounts into primitive `long` fields, which default to 0.

**Tests:** `LoansApiTest.loanWithAMissingOrInvalidFieldIsRejected` (case "no dailyRate") and `LoansApiTest.unknownFieldsAreIgnored` pin the current behaviour.

---

## F-12 · Error responses expose internal JSON parser messages

**Severity:** Low: leaks implementation details and confuses clients.

**Steps (2026-09-15, local run)**
- `POST /loans {"deviceId":"IMEI","price":"abc","dailyRate":100}` → `400 {"error":"invalid JSON: Cannot deserialize value of type \`long\` from String \"abc\": not a valid \`long\` value"}`
- `POST /payments` with an empty body → `400 {"error":"invalid JSON: No content to map due to end-of-input"}`

**Expected:** a stable message owned by the service, e.g. `{"error":"invalid JSON"}` or `{"error":"price must be a number"}`, with parser details only in the service log.

**Why:** `HttpApi.read` appends `JsonProcessingException.getOriginalMessage()` from Jackson to the response.

**Test:** `LoansApiTest.brokenJsonErrorDoesNotExposeParserDetails`, disabled until fixed.
