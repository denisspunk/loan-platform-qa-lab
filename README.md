# Loan Lab на Java

Учебный проект на один день: примитивный фреймворк автотестов для бэкенда кредитной платформы.
Цепочка под тестом: платёж → займ → телефон → relock через партнёра по блокировке.
Учебник с планом дня и уроками: https://claude.ai/artifact/Q8ysDDqUzbwK7cEvH5gLEb

- `starter/` — здесь работаем: готовый сервис в `src/main`, пустые пакеты каркаса и `FirstTest`.
- `solution/` — эталон: 33 теста на пяти уровнях пирамиды, все зелёные.
- `.github/workflows/pyramid.yml` — пирамида как стадии CI. Не запускался: у лабы нет GitHub-репозитория.

Сервис учебный: HTTP-сервер из JDK, Jackson, «Kafka» в памяти (`InMemoryEventBus`).
API партнёра выдуман для лабы и не совпадает с настоящим Samsung Knox.

## Что нужно

JDK 21+ и Maven. На этой машине стоят JDK 26.0.2.1 и Maven 3.9.16 из Homebrew (`mvn -v`).
Системная `java` в PATH — 16, в IDE укажи SDK `/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home`.

## Команды

```bash
cd solution                                   # или starter
mvn test                                      # все уровни
mvn test -Dgroups=unit                        # один уровень: unit | component | contract | integration | e2e
mvn test -Dgroups="integration | e2e"         # несколько уровней
mvn test -Dtest=UnlockPolicyTest              # один класс
mvn test -Dlab.bugs=KNOX_EPOCH_DATE           # включить засеянный баг
```

Тестов по уровням в `solution/`: unit 18, component 3, contract 4, integration 6, e2e 2.

## Стенд и база

- Сервис из `starter/` развёрнут на Render: https://loan-platform-qa-lab.onrender.com (`GET /health`). Деплой идёт после зелёного CI.
- С `DATABASE_URL` сервис хранит займы и обработанные платежи в Postgres, без неё — в памяти.
- Бесплатная база Render удаляется 15.10.2026.

Смотреть базу — `tools/db.sh`: psql из Docker, только чтение.

```bash
export RENDER_API_KEY=rnd_...                 # база на Render; или DATABASE_URL=postgresql://... для своей
tools/db.sh tables                            # таблицы и число строк
tools/db.sh loans 10                          # займы, сначала с последними платежами
tools/db.sh payments LN-6954bb4f              # платежи по займу: APPLIED, LOAN_ALREADY_PAID_OFF
tools/db.sh sql "SELECT count(*) FROM loans"  # любой запрос
tools/db.sh psql                              # интерактивный psql
```

## Слои каркаса (`src/test/java/lab/qa`)

| Пакет | Что внутри |
|---|---|
| `tests/` | тесты; один пакет = один уровень = один `@Tag` |
| `dsl/` | `LoanSteps`, `PaymentSteps`, `DeviceSteps`, `LoanAssert` — шаги и проверки словами бизнеса |
| `data/` | `TestData`: уникальные IMEI и paymentId, билдер `aLoan()` |
| `clients/` | `LoansApi` (RestAssured), `KnoxStub` (WireMock), JSON-модели |
| `core/` | `BaseIT` поднимает стаб и сервис, `TestClock`, `Config` |

## Засеянные баги

Каждый прогон — `mvn test -Dlab.bugs=<баг>` в `solution/`, 14.09.2026. Число — сколько тестов уровня покраснело.

| Баг | Что ломает | unit | component | contract | integration | e2e |
|---|---|---|---|---|---|---|
| `ROUNDING_UP` | дни округляются вверх | 6 | 2 | 0 | 1 | 1 |
| `DOUBLE_PROCESSING` | повторный paymentId применяется снова | 1 | 1 | 0 | 1 | 0 |
| `KNOX_EPOCH_DATE` | relockAt уходит партнёру числом, не ISO-строкой | 0 | 0 | 1 | 0 | 1 |
| `ZERO_AMOUNT_ACCEPTED` | платёж на 0 KES принимается | 0 | 0 | 0 | 1 | 0 |
