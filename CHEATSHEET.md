# Шпаргалка: что и как гонять локально

Личный файл, в git не идёт. Числа — с прогонов 2026-09-16 на коммите `e7b0385`.

## Перед началом

```bash
cd service                      # все mvn-команды отсюда
java -version                   # нужен 21+; у меня 26, это нормально
docker info                     # нужен ТОЛЬКО для Testcontainers (8 тестов) и tools/db.sh
```

Без Docker всё остальное работает. Если он выключен — добавляй `-Dtest='!JdbcLoanRepositoryTest'`.

## Пирамида: то, что гоняется без интернета и без стенда

```bash
mvn test                                        # 120 тестов, 3 @Disabled. Нужен Docker
mvn test -Dtest='!JdbcLoanRepositoryTest'       # 112 + 3 skip, без Docker
```

По одному уровню:

```bash
mvn test -Dgroups=unit                 # 35   правила разблокировки, займ, процессор
mvn test -Dgroups=component            #  5   шина + процессор, поддельный партнёр
mvn test -Dgroups=contract             # 18   что шлём партнёру + схемы ответов
mvn test -Dgroups=integration          # 59   сервис по HTTP + Postgres в Docker
mvn test -Dgroups=e2e                  #  3   путь клиента за несколько дней
```

Несколько уровней разом — кавычки обязательны:

```bash
mvn test -Dgroups="unit | component | contract"      # 58, без Docker
```

Один класс, один метод:

```bash
mvn test -Dtest=UnlockPolicyTest
mvn test -Dtest=UnlockPolicyTest#everyFullDailyRateBuysOneDay
```

## Тесты против стенда

Стендовые группы по умолчанию **исключены** (`excludedGroups` в pom), поэтому нужен `-DexcludedGroups=`.

```bash
STAGE=https://loan-platform-stage.onrender.com

mvn test -Dgroups="smoke | regression" -DexcludedGroups= -Dlab.baseUrl=$STAGE   # 28
mvn test -Dgroups="readonly"           -DexcludedGroups= -Dlab.baseUrl=$STAGE   #  9
mvn test -Dgroups="smoke | readonly"   -DexcludedGroups= -Dlab.baseUrl=$STAGE   # 13
```

| группа | что это | можно на проде? |
|---|---|---|
| `smoke` | стенд жив, займ открывается, платёж проходит | да (гейт 3) |
| `regression` | бизнес-правила по HTTP, **пишет в базу** | **нет** |
| `readonly` | подмножество regression, ничего не создаёт | да |

Адреса: dev `loan-platform-qa-lab`, stage `loan-platform-stage`, prod `loan-platform-prod`, все `.onrender.com`.

Free tier засыпает — первый запрос может думать до минуты, тесты это ждут.

## Находки

Тесты, прибивающие баг, помечены `@Tag("F-xx")` — оба теста пары, и зелёный, и `@Disabled`.

```bash
mvn test -Dgroups="F-04"     # 4 теста в двух уровнях, 2 из них skip (@Disabled)
mvn test -Dgroups="F-01"     # 4
```

## Подсаженные баги

```bash
mvn test -Dlab.bugs=ROUNDING_UP            # 8 unit, 2 component, 2 integration, 1 e2e
mvn test -Dlab.bugs=DOUBLE_PROCESSING      # 1 unit, 1 component, 1 integration
mvn test -Dlab.bugs=PARTNER_EPOCH_DATE        # 2 contract, 1 integration, 2 e2e
mvn test -Dlab.bugs=ZERO_AMOUNT_ACCEPTED   # 1 integration  <- юниты НЕ ловят, правило в HTTP-слое
```

Последний — лучшая иллюстрация на интервью: показывает, что уровень ловли определяется тем, где живёт правило, а не тем, сколько тестов написано.

## Покрытие

```bash
mvn test -Dgroups=unit
open target/site/jacoco/index.html          # покрытие ТОЛЬКО того, что прогнал
```

Помни: `mvn test -Dgroups=unit` даст ~24% строк, и это не повод пугаться — юниты не ходят по HTTP. Полная картина (88.1%) только в CI, в джобе `service-coverage`, где сливаются все четыре уровня.

`append=false` в pom, так что каждый прогон считает себя, а не сумму с прошлым.

## Скрипты

```bash
tools/findings.py                 # находки -> тесты: 8 pinned, 1 expected only, 3 document only
tools/traceability.py             # требования -> тесты: 57, все ссылки резолвятся
tools/findings.py --markdown      # то же таблицей, как в CI
```

Оба падают с ненулевым кодом, если документ разошёлся с тестами. Гоняются в CI в джобе `service-traceability`.

## Стенды и база

```bash
set -a; . ./.env; set +a          # ключи из .env, он тоже не в git

tools/render.sh live-commit "$RENDER_SERVICE_STAGE"     # какой коммит крутится
tools/db.sh --env stage tables                          # таблицы и число строк (нужен Docker)
tools/db.sh --env stage loans 10                        # последние займы
```

## Сервис локально

```bash
docker build -t loans service && docker run -p 8080:8080 loans
open http://localhost:8080          # веб-UI
curl localhost:8080/health
```

Без `DATABASE_URL` данные в памяти. Без `-Dpartner.url` партнёр — заглушка, которая просто печатает в лог (см. кандидата в F-13).

## Грабли, на которые уже наступал

- **JDK 26 + JaCoCo 0.8.13.** Агент не может инструментировать классы Java 26. Решено белым списком `lab.loans.*` в pom — Mockito и классы JDK под агент не попадают. В CI Java 21, там этого нет.
- **Docker выключен** → `JdbcLoanRepositoryTest` падает, а не пропускается. Исключай по имени.
- **Кавычки в `-Dgroups`.** `-Dgroups=unit | component` без кавычек — это пайп в шелле. Всегда `-Dgroups="unit | component"`.
- **`-DexcludedGroups=`** для стендовых тестов. Забудешь — прогонится ноль тестов и всё позеленеет.
- **Полный SHA** в формах GitHub Actions. Короткий не примут: `git rev-parse origin/main`.

## Что обычно хочется на демо

```bash
# 1. быстрый зелёный прогон без Docker
mvn test -Dtest='!JdbcLoanRepositoryTest'

# 2. подсаженный баг, который ловится только интеграцией  (проверено 2026-09-16)
mvn test -Dlab.bugs=ZERO_AMOUNT_ACCEPTED -Dgroups=unit
#   -> Tests run: 35, Failures: 0   зелено, юниты его не видят
mvn test -Dlab.bugs=ZERO_AMOUNT_ACCEPTED -Dgroups=integration -Dtest='!JdbcLoanRepositoryTest'
#   -> Tests run: 51, Failures: 1   красно. Без Docker нужен -Dtest='!JdbcLoanRepositoryTest'

# 3. всё про одну находку
mvn test -Dgroups="F-04"

# 4. документы против тестов
tools/traceability.py && tools/findings.py
```
