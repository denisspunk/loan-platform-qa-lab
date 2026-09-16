# Traceability matrix: requirements to tests

Which requirement each test defends, which levels defend it, and where nothing does.
Requirements are derived from the business rules and the API contract in [README.md](README.md); the service has no separate requirements document, so this file is that document.

Counted statically from the test sources on 2026-09-16, at commit `4442953`. The numbers match the pyramid table in the README: 120 tests in `mvn test` (unit, component, contract, integration, e2e), plus 4 smoke and 15 remote tests that run against a deployed stand.

## How to read this

| Column | Meaning |
|---|---|
| ID | `BR` business rule, `API` HTTP contract, `INT` device-lock partner, `EVT` asynchronous processing, `DATA` persistence, `OPS` delivery and stands |
| Levels | which pyramid levels touch this requirement: U unit, C component, K contract, I integration, E e2e, S smoke, R remote |
| Tests | `ClassName#method`; see the class index at the bottom for paths |
| Status | **Covered** — at least one level asserts it; **Partial** — asserted only in part, the gap is named; **Known bug** — the current behaviour is pinned and the expected one is `@Disabled`; **Uncovered** — no automated test |

A parameterized method counts as one entry here, but as several tests in the totals.

## Business rules

| ID | Requirement | Levels | Tests | Status |
|---|---|---|---|---|
| BR-01 | Every full daily rate buys one day; the remainder is kept as credit | U, C, E, R | `UnlockPolicyTest#everyFullDailyRateBuysOneDay` (8 cases), `PaymentProcessorTest#fullDayPaymentUnlocksAndSchedulesTheRelock`, `PhoneLoanJourneyTest#customerPaysDayByDayUntilThePhoneIsTheirs`, `RemoteApiTest#smallPaymentsBuyADayOnlyWhenTheyAddUp` | Covered |
| BR-02 | A payment below the daily rate only adds to the credit; the phone stays as it is | U, I, E, R | `UnlockPolicyTest#lessThanOneDayKeepsThePhoneAsItIs`, `LoanTest#keepDecisionOnlyAddsTheMoney`, `PaymentsApiTest#partialPaymentKeepsThePhoneLocked`, `PhoneLoanJourneyTest#smallPaymentsBuyADayOnlyWhenTheyAddUp`, `RemoteApiTest#smallPaymentsBuyADayOnlyWhenTheyAddUp` | Covered |
| BR-03 | Credit carries over and is added to the next payment | U, C, E | `UnlockPolicyTest#everyFullDailyRateBuysOneDay` (credit 50 + 50, credit 30 + 50), `PaymentConsumerTest#partialPaymentsAddUpInOrder`, `PhoneLoanJourneyTest#smallPaymentsBuyADayOnlyWhenTheyAddUp` | Covered |
| BR-04 | A locked or expired phone is unlocked from now; an unlocked phone has days added on top | U, E, R | `UnlockPolicyTest#lockedPhoneIsUnlockedFromNow`, `#newDaysAreAddedOnTopOfAnUnlockedPhone`, `#expiredUnlockCountsNewDaysFromNow`, `LoanTest#unlockDecisionUnlocksThePhone`, `PhoneLoanJourneyTest#payingAgainWhileUnlockedExtendsThePhone`, `RemoteApiTest#payingAgainWhileUnlockedAddsADay` | Covered |
| BR-05 | The phone relocks by itself exactly at `unlockedUntil`, without a stored change | U, E | `LoanTest#phoneRelocksExactlyAtUnlockedUntil` (−1 s, 0 s, +1 s), `#relockByTimeDoesNotChangeTheStoredState`, `PhoneLoanJourneyTest#payingAgainWhileUnlockedExtendsThePhone` | Covered |
| BR-06 | When the whole price is paid the loan is `PAID_OFF` and the phone `RELEASED` | U, I, E, R | `UnlockPolicyTest#paymentThatCoversThePriceReleasesThePhone` (299/300/350), `LoanTest#releaseDecisionPaysTheLoanOff`, `PaymentProcessorTest#paymentThatCoversThePriceReleasesTheLock`, `PaymentsApiTest#paymentThatCoversThePriceReleasesThePhone`, `PhoneLoanJourneyTest#customerPaysDayByDayUntilThePhoneIsTheirs`, `RemoteApiTest#paymentThatCoversThePriceReleasesThePhone` | Covered |
| BR-07 | A payment redelivered with the same `paymentId` is not applied twice | U, C, I, R | `PaymentProcessorTest#samePaymentIdIsAppliedOnlyOnce`, `PaymentConsumerTest#duplicateDeliveryIsAppliedOnce`, `PaymentsApiTest#duplicateCallbackIsAppliedOnce`, `LoanHistoryApiTest#duplicatePaymentAppearsOnce`, `RemoteApiTest#duplicateCallbackIsAppliedOnce` | Covered |
| BR-08 | A payment of zero or a negative amount is refused | U, I, R | `UnlockPolicyTest#nonPositiveAmountIsRejected` (0, −100), `PaymentsApiTest#nonPositiveAmountIsRejected` (0, −1), `RemoteApiTest#nonPositiveAmountIsRejected` (0, −1) | Covered |
| BR-09 | A new loan is `ACTIVE`, the phone `LOCKED`, the balance the whole price | U, I, S | `LoanTest#newLoanOwesTheWholePrice`, `LoansApiTest#newLoanStartsWithALockedPhone`, `StandSmokeTest#loanCanBeOpenedAndReadBack` | Covered |
| BR-10 | Price and daily rate must be positive | U, I, R | `LoanTest#priceAndDailyRateMustBePositive` (4 cases), `LoansApiTest#loanWithAMissingOrInvalidFieldIsRejected` (6 cases), `RemoteApiTest#invalidLoanIsRejected` (3 cases) | Covered |
| BR-11 | A payment to a paid-off loan changes nothing and is recorded as `LOAN_ALREADY_PAID_OFF` | I, R | `PaymentsApiTest#paymentToAPaidOffLoanChangesNothing`, `LoanHistoryApiTest#paymentToAPaidOffLoanIsRecordedAsAlreadyPaidOff`, `RemoteApiTest#paymentToAPaidOffLoanChangesNothing`, `#paymentHistoryShowsBothResults` | Known bug F-01: the money is accepted and lost; the tests pin today's behaviour, the product decision is open |
| BR-12 | Money paid above the price is tracked | U | `LoanTest#overpaymentShowsZeroBalance` | Known bug F-02: the test pins that the excess is invisible; the requirement itself is not implemented |
| BR-13 | The unlock is counted from the moment the customer paid (`receivedAt`), not from the moment the service processed the payment | — | — | **Uncovered** — F-09; `receivedAt` is carried through the event and ignored. Needs a product decision before a test can assert anything |

## HTTP API

| ID | Requirement | Levels | Tests | Status |
|---|---|---|---|---|
| API-01 | `POST /loans` answers `201` with the loan | K, I, S, R | `LoanApiSchemaTest#createdLoanMatchesTheLoanSchema`, `LoansApiTest#newLoanStartsWithALockedPhone`, `#createdLoanIsReadBackUnchanged`, `StandSmokeTest#loanCanBeOpenedAndReadBack`, `RemoteApiTest#responsesMatchTheirSchemas` | Covered |
| API-02 | `GET /loans/{id}` answers `200` with paid, balance, credit, status, deviceState, unlockedUntil | K, I | `LoanApiSchemaTest#loanMatchesTheLoanSchemaInEveryState` (LOCKED, UNLOCKED, RELEASED), `LoansApiTest#createdLoanIsReadBackUnchanged` | Covered |
| API-03 | `GET /loans?limit=N` answers newest first | K, I, R | `LoanApiSchemaTest#loansListMatchesItsSchema`, `LoanHistoryApiTest#loansListShowsTheNewestLoanFirst`, `#listedLoanShowsItsCurrentState`, `RemoteApiTest#openedLoanIsListed` | Covered |
| API-04 | `limit` is 1–100 and defaults to 20 | I | `LoanHistoryApiTest#loansListReturnsTwentyByDefault`, `#loansListRefusesAnInvalidLimit` (0, 101, −1, abc, empty) | **Partial** — only the refused values are asserted. The accepted boundaries `limit=1` and `limit=100` are never sent |
| API-05 | `GET /loans/{id}/payments` answers the history with `APPLIED` or `LOAN_ALREADY_PAID_OFF` | K, I, R | `LoanApiSchemaTest#paymentHistoryMatchesItsSchema`, `LoanHistoryApiTest#newLoanHasAnEmptyHistory`, `#historyListsAppliedPaymentsInOrder`, `RemoteApiTest#paymentHistoryShowsBothResults` | Covered |
| API-06 | `POST /payments` answers `202` and applies the payment asynchronously | K, I, S | `LoanApiSchemaTest#acceptedPaymentMatchesItsSchema`, `PaymentsApiTest#acceptedPaymentIsAnsweredWithItsId`, `StandSmokeTest#paymentUnlocksThePhoneForADay` | Covered |
| API-07 | A missing or blank required field is refused with `400` and a JSON error | K, I, R | `LoanApiSchemaTest#validationErrorMatchesTheErrorSchema`, `LoansApiTest#loanWithAMissingOrInvalidFieldIsRejected`, `PaymentsApiTest#paymentWithoutARequiredFieldIsRejected` (3 cases), `RemoteApiTest#invalidLoanIsRejected` | Covered |
| API-08 | A body that is not JSON is refused with `400` | I | `LoansApiTest#loanBodyThatIsNotJsonIsRejected`, `PaymentsApiTest#paymentBodyThatIsNotJsonIsRejected` | Covered |
| API-09 | An error response does not expose internal parser messages | I | `LoansApiTest#brokenJsonErrorDoesNotExposeParserDetails` | Known bug F-12: `@Disabled`, Jackson's message still leaks |
| API-10 | An unknown loan is `404` with a JSON error | K, I, R | `LoanApiSchemaTest#unknownLoanErrorMatchesTheErrorSchema`, `LoansApiTest#unknownLoanIsNotFound`, `PaymentsApiTest#paymentForUnknownLoanIsNotFound`, `LoanHistoryApiTest#historyOfAnUnknownLoanIsNotFound`, `RemoteApiTest#unknownLoanIsNotFound` | Covered |
| API-11 | An empty loan id is refused | I | `LoansApiTest#emptyLoanIdIsAnsweredAsAnUnknownLoan` | Known bug F-08: `404` instead of `400`; the test pins today's answer |
| API-12 | Unknown fields in a request body | I | `LoansApiTest#unknownFieldsAreIgnored` | Known bug F-11: silently ignored; the test pins it, the contract question is open |
| API-13 | A wrong method on a known path is refused with `404` and a JSON error | K, I, R | `LoanApiSchemaTest#wrongMethodErrorMatchesTheErrorSchema`, `HealthAndRoutingTest#wrongMethodOnAKnownPathIsRefused` (5 cases), `RemoteApiTest#wrongMethodIsRefused` | Covered |
| API-14 | A path outside the API is refused with a JSON error, not an HTML page | I | `HealthAndRoutingTest#pathOutsideTheApiIsRefusedWithAJsonError` (3 cases) | Covered — F-10 fixed, the `@Disabled` test was switched on |
| API-15 | Every response matches its published JSON schema | K, R | `LoanApiSchemaTest` (all 10 tests over `loan`, `loan-list`, `payment-accepted`, `payment-history`, `error`), `RemoteApiTest#responsesMatchTheirSchemas` | Covered |
| API-16 | `GET /health` answers `{"status":"UP"}` | I, S | `HealthAndRoutingTest#healthCheckAnswersUp`, `StandSmokeTest#healthCheckAnswersUp` | Covered |
| API-17 | `GET /` serves the web UI | I, S | `HealthAndRoutingTest#homePageServesTheWebUi`, `StandSmokeTest#webUiPageIsServed` | **Partial** — only that the page is served. Nothing drives the page itself; see the gaps below |

## Device-lock partner

| ID | Requirement | Levels | Tests | Status |
|---|---|---|---|---|
| INT-01 | `unlock` is sent once, as an empty JSON object with the correlation id | K, I | `KnoxClientContractTest#unlockSendsAnEmptyJsonObject`, `PaymentsApiTest#acceptedPaymentUnlocksThePhoneThroughThePartner` | Covered |
| INT-02 | The relock moment is sent as an ISO-8601 string, once | K, U, E | `KnoxClientContractTest#relockSendsAnIsoInstant`, `PaymentProcessorTest#fullDayPaymentUnlocksAndSchedulesTheRelock`, `PhoneLoanJourneyTest#payingAgainWhileUnlockedExtendsThePhone` | Covered — this is the pair that catches `KNOX_EPOCH_DATE` |
| INT-03 | The relock moment keeps fractions of a second | K | `KnoxClientContractTest#relockKeepsFractionsOfASecond` | Covered — F-07 pinned as today's behaviour; the partner contract question is open |
| INT-04 | `release` is sent once when the loan is paid off | K, U, I | `KnoxClientContractTest#releaseSendsAnEmptyJsonObject`, `PaymentProcessorTest#paymentThatCoversThePriceReleasesTheLock`, `PaymentsApiTest#paymentThatCoversThePriceReleasesThePhone` | Covered |
| INT-05 | A partner error on any action becomes a `DeviceLockException` | K | `KnoxClientContractTest#partnerErrorBecomesDeviceLockException` (unlock, relock, release), `#unreachablePartnerBecomesDeviceLockException` | Covered |
| INT-06 | A partial payment calls the partner not at all | U, I | `PaymentProcessorTest#partialPaymentDoesNotCallThePartner`, `PaymentsApiTest#partialPaymentKeepsThePhoneLocked` | Covered |
| INT-07 | A partner failure between unlock and relock must not leave the phone unlocked | C, I | `PaymentConsumerTest#partnerFailureOnRelockLeavesTheLoanUnchanged` + `#phoneIsNotUnlockedWhenTheRelockFails`, `PaymentsApiTest#partnerFailureLeavesTheLoanUnchanged` + `#phoneIsNotUnlockedWhenTheRelockFails` | Known bug F-04: pinned twice at two levels, the expected behaviour `@Disabled` at both |
| INT-08 | A scheduled relock is cancelled when the loan is paid off early | — | — | **Uncovered** — F-03, critical if the partner does not cancel it by itself. Blocked on the partner contract |
| INT-09 | `unlock` is not sent again for a phone that is already unlocked | — | — | **Uncovered** — F-06; also happens on every retry of F-04 |

## Asynchronous processing

| ID | Requirement | Levels | Tests | Status |
|---|---|---|---|---|
| EVT-01 | A payment accepted over HTTP reaches the processor and changes the loan | C, I, E, S | `PaymentConsumerTest#duplicateDeliveryIsAppliedOnce`, `PaymentsApiTest#acceptedPaymentUnlocksThePhoneThroughThePartner`, `PhoneLoanJourneyTest` (all 3), `StandSmokeTest#paymentUnlocksThePhoneForADay` | Covered |
| EVT-02 | Partial payments of one loan are applied in the order they arrive | C | `PaymentConsumerTest#partialPaymentsAddUpInOrder` | Covered |
| EVT-03 | An event that cannot be processed goes to dead letters after 3 attempts and does not block the queue | C | `PaymentConsumerTest#poisonEventsGoToDeadLettersAndTheNextEventsStillFlow`, `#partnerFailureOnRelockLeavesTheLoanUnchanged` | Covered |
| EVT-04 | A payment for an unknown loan fails with no side effects | U, I | `PaymentProcessorTest#paymentForUnknownLoanFailsWithoutSideEffects`, `PaymentsApiTest#paymentForUnknownLoanIsNotFound` | Covered |
| EVT-05 | Two payments for one loan arriving at the same time do not corrupt the loan | — | — | **Uncovered** — nothing sends concurrent payments for one loan. See the gaps below |

## Persistence

| ID | Requirement | Levels | Tests | Status |
|---|---|---|---|---|
| DATA-01 | A loan is read back from Postgres exactly as it was saved | I | `JdbcLoanRepositoryTest#newLoanIsReadBackAsSaved` | Covered |
| DATA-02 | A processed payment stores the loan state it produced | I | `JdbcLoanRepositoryTest#recordedPaymentStoresTheLoanStateAndIsRemembered` | Covered |
| DATA-03 | Duplicate protection survives a restart, because it lives in the database | I | `JdbcLoanRepositoryTest#samePaymentRecordedTwiceIsNotAnError`, `#recordedPaymentStoresTheLoanStateAndIsRemembered` | **Partial** — F-05 is fixed and the storage is asserted, but no test restarts the service and replays the payment. Verified by hand on Render |
| DATA-04 | Recent loans come newest first | I | `JdbcLoanRepositoryTest#recentLoansComeNewestFirst` | Covered |
| DATA-05 | The payments of a loan come in processing order, without other loans' payments | I | `JdbcLoanRepositoryTest#paymentsComeInProcessingOrder` | Covered |
| DATA-06 | An unknown loan or payment is simply not found | I | `JdbcLoanRepositoryTest#unknownLoanAndPaymentAreNotFound` | Covered |
| DATA-07 | The database itself refuses a paid-off loan whose phone is still locked | I | `JdbcLoanRepositoryTest#databaseRejectsAPaidOffLoanWithALockedPhone` | Covered |
| DATA-08 | A Render-style `postgresql://` URL is understood | I | `JdbcLoanRepositoryTest#renderStyleDatabaseUrlIsUnderstood` | Covered |
| DATA-09 | Without `DATABASE_URL` the service keeps data in memory | U, C, I | used by every test that does not start Testcontainers | Covered implicitly — no test asserts the fallback itself |

## Delivery and stands

| ID | Requirement | Levels | Tests | Status |
|---|---|---|---|---|
| OPS-01 | A deployed stand is up and serves the UI | S | `StandSmokeTest#healthCheckAnswersUp`, `#webUiPageIsServed` | Covered — gates 1, 2 and 3 |
| OPS-02 | The business rules hold on a deployed stand, over HTTP only | R | `RemoteApiTest` (12 methods, 15 tests) | Covered — dev and stage |
| OPS-03 | `main` accepts only pull requests that pass the four pyramid stages | — | `.github/workflows` + branch protection | Covered by configuration, not by a test |
| OPS-04 | A failed gate 3 rolls prod back to the previous commit | — | `delivery` workflow | **Uncovered** — the rollback path has never been exercised on purpose |

## Coverage summary

| Group | Requirements | Covered | Partial | Known bug | Uncovered |
|---|---|---|---|---|---|
| BR — business rules | 13 | 10 | 0 | 2 | 1 |
| API — HTTP contract | 17 | 12 | 2 | 3 | 0 |
| INT — partner | 9 | 6 | 0 | 1 | 2 |
| EVT — asynchronous | 5 | 4 | 0 | 0 | 1 |
| DATA — persistence | 9 | 8 | 1 | 0 | 0 |
| OPS — delivery | 4 | 3 | 0 | 0 | 1 |
| **Total** | **57** | **43** | **3** | **6** | **5** |

OPS-03 counts as covered, by branch protection rather than by a test; its row says so.

Tests behind those requirements, by level:

| Level | Tests | Classes |
|---|---|---|
| unit | 35 | `UnlockPolicyTest` 17, `LoanTest` 13, `PaymentProcessorTest` 5 |
| component | 5 | `PaymentConsumerTest` 5 (1 `@Disabled`) |
| contract | 18 | `KnoxClientContractTest` 8, `LoanApiSchemaTest` 10 |
| integration | 59 | `PaymentsApiTest` 15 (1 `@Disabled`), `LoansApiTest` 13 (1 `@Disabled`), `LoanHistoryApiTest` 13, `HealthAndRoutingTest` 10, `JdbcLoanRepositoryTest` 8 |
| e2e | 3 | `PhoneLoanJourneyTest` 3 |
| smoke | 4 | `StandSmokeTest` 4 |
| remote | 15 | `RemoteApiTest` 15 |

## Gaps, ordered by what they would cost to miss

1. **INT-08 — the relock is not cancelled on an early payoff (F-03).** The phone can lock itself after the customer owns it. Nothing tests it because the partner's cancel semantics are unknown; the first step is the answer from the partner, not a test.
2. **EVT-05 — concurrent payments for one loan.** The whole duplicate story is tested sequentially. Two callbacks arriving together is exactly how a payment provider behaves under load, and no level would notice a lost update.
3. **API-17 — the web UI is only checked to be served.** Every rule the page shows is covered through the API it calls, but nothing asserts that the page renders them. Playwright e2e over the UI is the planned next step.
4. **DATA-03 — durability across a restart (F-05).** The fix is in, the storage is asserted, but the restart itself is only verified by hand. A Testcontainers test that stops the service and replays the same `paymentId` would close it.
5. **INT-09 — a repeated `unlock` (F-06)** and **BR-13 — `receivedAt` is ignored (F-09).** Both are open questions to the partner and to product; today's behaviour is understood but deliberately unpinned.
6. **API-04 — the accepted `limit` boundaries.** Two cases, `limit=1` and `limit=100`, would make the validation rule symmetric. Cheap, and the only pure oversight on this list.
7. **OPS-04 — the prod rollback.** Exercised only by a real failure, which is the worst time to find out it does not work.

## Findings back-trace

Generated by `tools/findings.py`, which reads the `@Tag("F-xx")` on the tests and the summary
table of [BUGS.md](BUGS.md). Both tests of a pair carry the tag: the passing one that pins
today's behaviour, and the `@Disabled` one that states the behaviour we want instead.

- **pinned** — at least one passing test holds the current behaviour in place
- **expected only** — the wanted behaviour is written down as a `@Disabled` test, but nothing
  guards what the service does today, so the bug can change shape unnoticed
- **document only** — the finding lives in BUGS.md and nowhere else

Severity and the current status of each finding stay in [BUGS.md](BUGS.md); this table is only
about the link to the tests. `tools/findings.py` runs in CI before the unit tests and fails the
build when a tag names a finding BUGS.md does not list, or when a test's display name mentions
`(F-xx)` without the matching tag.

`mvn test -Dgroups="F-04"` runs everything about one finding, across every level that pins it.

| Finding | Pinned by | Expected behaviour | State |
|---|---|---|---|
| F-01 Payment for a paid-off loan is accepted and silently dropped | LoanHistoryApiTest#paymentToAPaidOffLoanIsRecordedAsAlreadyPaidOff, PaymentsApiTest#paymentToAPaidOffLoanChangesNothing, RemoteApiTest#paymentHistoryShowsBothResults, RemoteApiTest#paymentToAPaidOffLoanChangesNothing | — | pinned |
| F-02 Overpayment on the final payment is not tracked | LoanTest#overpaymentShowsZeroBalance | — | pinned |
| F-03 Scheduled relock is not cancelled when the loan is paid off | — | — | document only |
| F-04 Partner failure between unlock and relock leaves the phone unlocked for good | PaymentConsumerTest#partnerFailureOnRelockLeavesTheLoanUnchanged, PaymentsApiTest#partnerFailureLeavesTheLoanUnchanged | PaymentConsumerTest#phoneIsNotUnlockedWhenTheRelockFails, PaymentsApiTest#phoneIsNotUnlockedWhenTheRelockFails | pinned |
| F-05 Duplicate protection lives in memory and is lost on restart | JdbcLoanRepositoryTest#samePaymentRecordedTwiceIsNotAnError | — | pinned |
| F-06 Unlock is sent again for a phone that is already unlocked | — | — | document only |
| F-07 `relockAt` is sent with microseconds; the contract example has whole seconds | KnoxClientContractTest#relockKeepsFractionsOfASecond | — | pinned |
| F-08 `GET /loans/` with an empty id returns 404 instead of 400 | LoansApiTest#emptyLoanIdIsAnsweredAsAnUnknownLoan | — | pinned |
| F-09 `receivedAt` is ignored; unlock time counts from processing, not from payment | — | — | document only |
| F-10 A path outside the API gets an HTML 404 page instead of a JSON error | HealthAndRoutingTest#pathOutsideTheApiIsRefusedWithAJsonError | — | pinned |
| F-11 A missing or misspelled field is reported as an invalid value; unknown fields are silently ignored | LoansApiTest#loanWithAMissingOrInvalidFieldIsRejected, LoansApiTest#unknownFieldsAreIgnored | — | pinned |
| F-12 Error responses expose internal JSON parser messages | — | LoansApiTest#brokenJsonErrorDoesNotExposeParserDetails | expected only |

## Seeded bugs against requirements

`mvn test -Dlab.bugs=<bug>` breaks a rule on purpose and shows which level notices first. Numbers are red tests per level, measured on 2026-09-15.

| Seeded bug | Requirement it breaks | unit | component | contract | integration | e2e | Caught first at |
|---|---|---|---|---|---|---|---|
| `ROUNDING_UP` | BR-01 | 8 | 2 | 0 | 2 | 1 | unit |
| `DOUBLE_PROCESSING` | BR-07 | 1 | 1 | 0 | 1 | 0 | unit |
| `KNOX_EPOCH_DATE` | INT-02 | 0 | 0 | 2 | 1 | 2 | contract |
| `ZERO_AMOUNT_ACCEPTED` | BR-08 | 0 | 0 | 0 | 1 | 0 | integration |

`ZERO_AMOUNT_ACCEPTED` is the interesting one: the rule is validated in the HTTP layer, so the unit tests of `UnlockPolicy` stay green and only an integration test catches it. That is the shape of the gap, not a flaw in the pyramid.

## Class index

| Class | Path |
|---|---|
| `UnlockPolicyTest` | [unit/UnlockPolicyTest.java](service/src/test/java/lab/qa/tests/unit/UnlockPolicyTest.java) |
| `LoanTest` | [unit/LoanTest.java](service/src/test/java/lab/qa/tests/unit/LoanTest.java) |
| `PaymentProcessorTest` | [unit/PaymentProcessorTest.java](service/src/test/java/lab/qa/tests/unit/PaymentProcessorTest.java) |
| `PaymentConsumerTest` | [component/PaymentConsumerTest.java](service/src/test/java/lab/qa/tests/component/PaymentConsumerTest.java) |
| `KnoxClientContractTest` | [contract/KnoxClientContractTest.java](service/src/test/java/lab/qa/tests/contract/KnoxClientContractTest.java) |
| `LoanApiSchemaTest` | [contract/LoanApiSchemaTest.java](service/src/test/java/lab/qa/tests/contract/LoanApiSchemaTest.java) |
| `LoansApiTest` | [integration/LoansApiTest.java](service/src/test/java/lab/qa/tests/integration/LoansApiTest.java) |
| `PaymentsApiTest` | [integration/PaymentsApiTest.java](service/src/test/java/lab/qa/tests/integration/PaymentsApiTest.java) |
| `LoanHistoryApiTest` | [integration/LoanHistoryApiTest.java](service/src/test/java/lab/qa/tests/integration/LoanHistoryApiTest.java) |
| `HealthAndRoutingTest` | [integration/HealthAndRoutingTest.java](service/src/test/java/lab/qa/tests/integration/HealthAndRoutingTest.java) |
| `JdbcLoanRepositoryTest` | [integration/JdbcLoanRepositoryTest.java](service/src/test/java/lab/qa/tests/integration/JdbcLoanRepositoryTest.java) |
| `PhoneLoanJourneyTest` | [e2e/PhoneLoanJourneyTest.java](service/src/test/java/lab/qa/tests/e2e/PhoneLoanJourneyTest.java) |
| `StandSmokeTest` | [smoke/StandSmokeTest.java](service/src/test/java/lab/qa/tests/smoke/StandSmokeTest.java) |
| `RemoteApiTest` | [remote/RemoteApiTest.java](service/src/test/java/lab/qa/tests/remote/RemoteApiTest.java) |

## Keeping this file honest

Two rules, both cheap:

- A new test either lands under an existing requirement ID or brings a new one. If it fits nowhere, that is the finding.
- A requirement that moves out of **Uncovered** or **Known bug** moves here in the same pull request as the test that moved it.

The per-level counts come from the test sources; after a full run they can be checked against `service/target/surefire-reports`.
