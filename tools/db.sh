#!/usr/bin/env bash
# Look into the loan service database with psql from Docker: nothing to install, read-only by default.
#
#   tools/db.sh tables               tables and their row counts
#   tools/db.sh loans [N]            N loans, most recently paid first (default 20)
#   tools/db.sh payments [LOAN_ID]   processed payments, all or for one loan, newest first
#   tools/db.sh sql "SELECT ..."     any query
#   tools/db.sh psql                 interactive psql session
#
# Which database:
#   DATABASE_URL=postgresql://user:password@host:port/db   the one you name, e.g. local Postgres in Docker
#   RENDER_API_KEY=rnd_...                                  otherwise the Render database of this lab;
#                                                           the connection string is fetched on every run
#   RENDER_POSTGRES_ID=dpg-...                              a different Render database
#
# Every session runs with default_transaction_read_only=on, so a stray UPDATE fails instead of changing data.
set -euo pipefail

POSTGRES_IMAGE="postgres:17-alpine"
RENDER_POSTGRES_ID="${RENDER_POSTGRES_ID:-dpg-dakje7uk1f9s73dh8vt0-a}"

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

connection_url() {
    if [[ -n "${DATABASE_URL:-}" ]]; then
        # inside the psql container "localhost" is the container itself, so point it at the host machine
        echo "$DATABASE_URL" | sed -E 's#@(localhost|127\.0\.0\.1)([:/])#@host.docker.internal\2#'
        return
    fi
    if [[ -z "${RENDER_API_KEY:-}" ]]; then
        echo "Set DATABASE_URL, or RENDER_API_KEY to use the Render database." >&2
        exit 1
    fi
    local url
    url=$(curl -fsS -H "Authorization: Bearer $RENDER_API_KEY" -H "Accept: application/json" \
            "https://api.render.com/v1/postgres/$RENDER_POSTGRES_ID/connection-info" \
        | python3 -c 'import sys, json; print(json.load(sys.stdin)["externalConnectionString"])') || {
        echo "Could not get the connection string for $RENDER_POSTGRES_ID from the Render API." >&2
        exit 1
    }
    echo "${url}?sslmode=require"
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
        [[ -n "${1:-}" ]] || { echo "Usage: tools/db.sh sql \"SELECT ...\"" >&2; exit 1; }
        echo "$1" | run_psql
        ;;
    psql)
        run_psql --interactive
        ;;
    *)
        usage
        ;;
esac
