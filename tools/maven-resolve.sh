#!/usr/bin/env bash
# Downloads the dependencies and plugins of the Maven project in the current directory before any test runs.
# A Maven Central hiccup then fails this step, clearly as infrastructure, instead of turning a test stage red.
#
#   tools/maven-resolve.sh [ATTEMPTS]      default 3; waits 20 s, 40 s, ... between attempts
#
# Maven Resolver retries only I/O errors and HTTP 429/503 by itself, and it remembers a 404 ("not found"),
# so every retry here runs with -U to ask the repository again.
set -euo pipefail

attempts="${1:-3}"
delay="${MAVEN_RESOLVE_DELAY_SECONDS:-20}"

for attempt in $(seq 1 "$attempts"); do
    flags=(-B -ntp)
    if (( attempt > 1 )); then
        flags+=(-U)
    fi
    echo "Resolving dependencies, attempt $attempt of $attempts"
    if mvn "${flags[@]}" dependency:go-offline test-compile; then
        exit 0
    fi
    if (( attempt < attempts )); then
        wait_seconds=$((delay * attempt))
        echo "::warning::dependency resolution failed, retrying in ${wait_seconds}s"
        sleep "$wait_seconds"
    fi
done

echo "::error::dependencies could not be resolved after $attempts attempts: an infrastructure problem, not a test failure"
exit 1
