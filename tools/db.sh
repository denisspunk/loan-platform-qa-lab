#!/usr/bin/env bash
# Look into the loan service database with psql from Docker: nothing to install, read-only by default.
#
#   tools/db.sh [--env dev|stage|prod] tables               tables and their row counts
#   tools/db.sh [--env dev|stage|prod] loans [N]            N loans, most recently paid first (default 20)
#   tools/db.sh [--env dev|stage|prod] payments [LOAN_ID]   processed payments, all or for one loan, newest first
#   tools/db.sh [--env dev|stage|prod] sql "SELECT ..."     any query
#   tools/db.sh [--env dev|stage|prod] psql                 interactive psql session
#
# Which database:
#   --env dev|stage|prod     the Neon database of that environment: DATABASE_URL_DEV and so on, read from .env
#   DATABASE_URL=postgresql://user:password@host:port/db    otherwise the one you name, e.g. local Postgres in Docker
#
# Every session runs with default_transaction_read_only=on, so a stray UPDATE fails instead of changing data.
set -euo pipefail

POSTGRES_IMAGE="postgres:17-alpine"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

if [[ "${1:-}" == "--env" ]]; then
    env_name="${2:-}"
    [[ "$env_name" =~ ^(dev|stage|prod)$ ]] || { echo "--env must be dev, stage or prod, got: ${env_name:-nothing}" >&2; exit 1; }
    [[ -f "$REPO_ROOT/.env" ]] || { echo "No .env in $REPO_ROOT: copy .env.example to .env and fill it in." >&2; exit 1; }
    set -a
    # shellcheck disable=SC1091
    . "$REPO_ROOT/.env"
    set +a
    url_var="DATABASE_URL_$(echo "$env_name" | tr '[:lower:]' '[:upper:]')"
    DATABASE_URL="${!url_var:-}"
    [[ -n "$DATABASE_URL" ]] || { echo "$url_var is empty in .env" >&2; exit 1; }
    shift 2
fi

# Checked here, not inside connection_url: an exit inside $(...) would only leave the subshell.
if [[ -z "${DATABASE_URL:-}" ]]; then
    echo "Pass --env dev|stage|prod, or set DATABASE_URL." >&2
    exit 1
fi

connection_url() {
    # inside the psql container "localhost" is the container itself, so point it at the host machine
    echo "$DATABASE_URL" | sed -E 's#@(localhost|127\.0\.0\.1)([:/])#@host.docker.internal\2#'
}

# Runs psql in Docker. SQL comes from stdin, so psql variables such as :'loan_id' are substituted safely.
run_psql() {
    local tty_flags="-i"
    [[ "${1:-}" == "--interactive" ]] && { tty_flags="-it"; shift; }
    docker run --rm $tty_flags \
        --add-host=host.docker.internal:host-gateway \
        -e PGURL="$(connection_url)" \
        -e PGOPTIONS="-c default_transaction_read_only=on" \
        "$POSTGRES_IMAGE" \
        sh -c 'exec psql "$PGURL" -v ON_ERROR_STOP=1 "$@"' psql "$@"
}

command="${1:-}"
[[ -z "$command" ]] && usage
shift

case "$command" in
    tables)
        run_psql <<'SQL'
SELECT 'loans' AS "table", count(*) AS rows FROM loans
UNION ALL
SELECT 'processed_payments', count(*) FROM processed_payments;
SQL
        ;;
    loans)
        limit="${1:-20}"
        [[ "$limit" =~ ^[0-9]+$ ]] || { echo "N must be a number, got: $limit" >&2; exit 1; }
        run_psql -v limit="$limit" <<'SQL'
SELECT l.id, l.device_id, l.price, l.daily_rate, l.paid, l.credit, l.status, l.device_state,
       l.unlocked_until, max(p.processed_at) AS last_payment
FROM loans l
LEFT JOIN processed_payments p ON p.loan_id = l.id
GROUP BY l.id
ORDER BY last_payment DESC NULLS LAST, l.id
LIMIT :limit;
SQL
        ;;
    payments)
        if [[ -n "${1:-}" ]]; then
            run_psql -v loan_id="$1" <<'SQL'
SELECT payment_id, loan_id, amount, result, processed_at
FROM processed_payments
WHERE loan_id = :'loan_id'
ORDER BY processed_at DESC;
SQL
        else
            run_psql <<'SQL'
SELECT payment_id, loan_id, amount, result, processed_at
FROM processed_payments
ORDER BY processed_at DESC
LIMIT 50;
SQL
        fi
        ;;
    sql)
        [[ -n "${1:-}" ]] || { echo "Usage: tools/db.sh [--env dev|stage|prod] sql \"SELECT ...\"" >&2; exit 1; }
        echo "$1" | run_psql
        ;;
    psql)
        run_psql --interactive
        ;;
    *)
        usage
        ;;
esac
