#!/usr/bin/env bash
# Render deploys for the delivery pipeline and for local use. Needs RENDER_API_KEY in the environment.
#
#   tools/render.sh deploy SERVICE_ID COMMIT_SHA [TIMEOUT_SECONDS]   deploy one commit and wait until it is live
#   tools/render.sh live-commit SERVICE_ID                           print the commit of the service's live deploy
#
# Locally:  set -a; . ./.env; set +a; tools/render.sh live-commit "$RENDER_SERVICE_DEV"
set -euo pipefail

API="https://api.render.com/v1"

usage() {
    sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[[ -n "${RENDER_API_KEY:-}" ]] || { echo "RENDER_API_KEY is required" >&2; exit 1; }

render() {
    curl -fsS -H "Authorization: Bearer $RENDER_API_KEY" -H "Accept: application/json" "$@"
}

live_commit() {
    local service="$1"
    render "$API/services/$service/deploys?limit=20" | python3 -c '
import sys, json
for item in json.load(sys.stdin):
    deploy = item.get("deploy", item)
    if deploy.get("status") == "live":
        print((deploy.get("commit") or {}).get("id", ""))
        break
'
}

deploy() {
    local service="$1" commit="$2" timeout="${3:-900}"
    [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "COMMIT_SHA must be a full 40-character SHA, got: $commit" >&2; exit 1; }

    local deploy_id
    deploy_id=$(render -X POST -H "Content-Type: application/json" "$API/services/$service/deploys" \
        -d "{\"commitId\": \"$commit\"}" | python3 -c 'import sys, json; print(json.load(sys.stdin)["id"])')
    echo "deploy $deploy_id: commit $commit to service $service"

    local deadline=$((SECONDS + timeout)) last="" status
    while (( SECONDS < deadline )); do
        status=$(render "$API/services/$service/deploys/$deploy_id" | python3 -c 'import sys, json; print(json.load(sys.stdin)["status"])')
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
    echo "::error::deploy $deploy_id is not live after ${timeout}s" >&2
    return 1
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
