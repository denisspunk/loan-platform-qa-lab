#!/usr/bin/env bash
# Render deploys for the delivery pipeline and for local use. Needs RENDER_API_KEY in the environment.
#
#   tools/render.sh deploy SERVICE_ID COMMIT_SHA [TIMEOUT_SECONDS]   deploy one commit and wait until it is live
#   tools/render.sh live-commit SERVICE_ID                           print the commit of the service's live deploy
#
# Locally:  set -a; . ./.env; set +a; tools/render.sh live-commit "$RENDER_SERVICE_DEV"
set -euo pipefail

API="https://api.render.com/v1"
ATTEMPTS=3

usage() {
    sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[[ -n "${RENDER_API_KEY:-}" ]] || { echo "RENDER_API_KEY is required" >&2; exit 1; }

# Prints the response body, or explains what the API said and fails.
#
# Not `curl -f`: that prints nothing and returns 22 for any HTTP error, so the caller parses an
# empty string and dies with a JSON traceback that names neither the status nor the reason. Here
# the status and the body are read separately and reported. 429 and 5xx are retried, because the
# API rate-limits and a deploy that is already running answers while it settles.
render() {
    local url="" arg response status body problem attempt=1
    for arg in "$@"; do
        [[ "$arg" == https://* ]] && { url="$arg"; break; }
    done

    local problem
    problem=$(mktemp)
    trap 'rm -f "$problem"' RETURN

    while :; do
        # curl's own errors go to their own file: merged into stdout they would corrupt the body
        if response=$(curl -sS --max-time 30 -w $'\n%{http_code}' \
            -H "Authorization: Bearer $RENDER_API_KEY" -H "Accept: application/json" "$@" 2>"$problem"); then
            status="${response##*$'\n'}"
            body="${response%$'\n'*}"
        else
            status="000"
            body="$(cat "$problem")"
        fi

        if [[ "$status" =~ ^2 ]]; then
            printf '%s' "$body"
            return 0
        fi

        if (( attempt < ATTEMPTS )) && [[ "$status" == "000" || "$status" == "429" || "$status" =~ ^5 ]]; then
            echo "  Render API answered $status, attempt $attempt of $ATTEMPTS, retrying in $((attempt * 5))s" >&2
            sleep $((attempt * 5))
            attempt=$((attempt + 1))
            continue
        fi

        echo "::error::Render API answered $status for ${url:-the request}" >&2
        [[ -n "$body" ]] && echo "  ${body:0:800}" >&2
        return 1
    done
}

# render first, parse second: piping straight into python means a failed request still runs the
# parser on an empty stdin, and the traceback buries the message render just printed
field() {
    local body="$1" expression="$2" value
    value=$(printf '%s' "$body" | python3 -c "import sys, json; d = json.load(sys.stdin); print($expression)" 2>/dev/null) || {
        echo "::error::could not read $expression from the Render answer: ${body:0:400}" >&2
        return 1
    }
    printf '%s' "$value"
}

deploy_status() {
    local body
    body=$(render "$API/services/$1/deploys/$2") || return 1
    field "$body" 'd.get("status", "")'
}

live_commit() {
    local service="$1" body
    body=$(render "$API/services/$service/deploys?limit=20") || return 1
    printf '%s' "$body" | python3 -c '
import sys, json
for item in json.load(sys.stdin):
    deploy = item.get("deploy", item)
    if deploy.get("status") == "live":
        print((deploy.get("commit") or {}).get("id", ""))
        break
'
}

# Giving up on the wait must not leave the deploy running: the pipeline would report a failure
# while Render quietly changes the stand minutes later, and the next delivery would collide with
# it. On a timeout the deploy is cancelled, and if it won the race anyway that is said out loud,
# because then the stand HAS changed and the caller has to deal with it.
abandon() {
    local service="$1" deploy_id="$2" commit="$3" timeout="$4" status

    echo "::error::deploy $deploy_id is not live after ${timeout}s" >&2
    if render -X POST "$API/services/$service/deploys/$deploy_id/cancel" >/dev/null 2>&1; then
        echo "  cancel requested" >&2
    else
        echo "  cancel was refused; the deploy may already have finished" >&2
    fi

    sleep 5
    status=$(deploy_status "$service" "$deploy_id" 2>/dev/null || echo unknown)
    if [[ "$status" == "live" ]]; then
        echo "::error::the deploy went live anyway: the service is now running $commit, untested by this run" >&2
    else
        echo "  final status: $status; the service was left as it was" >&2
    fi
    return 1
}

deploy() {
    local service="$1" commit="$2" timeout="${3:-900}"
    [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "COMMIT_SHA must be a full 40-character SHA, got: $commit" >&2; exit 1; }

    local created deploy_id
    created=$(render -X POST -H "Content-Type: application/json" "$API/services/$service/deploys" \
        -d "{\"commitId\": \"$commit\"}") || return 1
    deploy_id=$(field "$created" 'd.get("id", "")') || return 1
    [[ -n "$deploy_id" ]] || { echo "::error::Render accepted the request but named no deploy: ${created:0:400}" >&2; return 1; }
    echo "deploy $deploy_id: commit $commit to service $service"

    local deadline=$((SECONDS + timeout)) last="" status
    while (( SECONDS < deadline )); do
        status=$(deploy_status "$service" "$deploy_id")
        if [[ "$status" != "$last" ]]; then
            echo "  status: $status"
            last="$status"
        fi
        case "$status" in
            live)
                return 0
                ;;
            build_failed|update_failed|canceled|deactivated|pre_deploy_failed)
                echo "::error::deploy $deploy_id ended as $status" >&2
                return 1
                ;;
        esac
        sleep 15
    done

    abandon "$service" "$deploy_id" "$commit" "$timeout"
}

case "${1:-}" in
    deploy)
        [[ $# -ge 3 ]] || usage
        deploy "$2" "$3" "${4:-900}"
        ;;
    live-commit)
        [[ $# -ge 2 ]] || usage
        live_commit "$2"
        ;;
    *)
        usage
        ;;
esac
