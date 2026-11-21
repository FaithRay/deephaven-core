#!/usr/bin/env bash

# Stop the Groovy server started by run-on-offline.sh.
# Unpack first, then run this script from that directory:
#   ~/deephaven/stop-on-offline.sh
# SIGINT is the server shutdown signal. SIGTERM and SIGKILL follow
# if the process is still up.

set -o errexit
set -o pipefail
set -o nounset

usage() {
    cat <<'EOF'
Usage: stop-on-offline.sh

  Stop the packed Groovy server started by run-on-offline.sh.
  Sends SIGINT, then SIGTERM, then SIGKILL if the process stays up.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi
if [[ $# -ne 0 ]]; then
    usage
    exit 2
fi

BUNDLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${BUNDLE_DIR}/run-on-offline.pid"
JAVA_BIN="${BUNDLE_DIR}/jdk/bin/java"

is_server_pid() {
    local pid="$1"
    local args
    [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
    if ! kill -0 "${pid}" 2>/dev/null; then
        return 1
    fi
    if [[ -r "/proc/${pid}/cmdline" ]]; then
        args="$(tr '\0' ' ' < "/proc/${pid}/cmdline" || true)"
    else
        args="$(ps -p "${pid}" -ww -o args= 2>/dev/null || true)"
    fi
    case "${args}" in
        *"io.deephaven.server.jetty.JettyMain"*)
            case "${args}" in
                *"${BUNDLE_DIR}/server-jetty"*) return 0 ;;
            esac
            ;;
    esac
    # ps truncates a long classpath before the main class. The bundled java
    # binary and Database.root are both at the front of the command.
    case "${args}" in
        "${JAVA_BIN} "*)
            case "${args}" in
                *"-DDatabase.root="*) return 0 ;;
            esac
            ;;
    esac
    return 1
}

find_server_pids() {
    local entry pid
    if [[ -d /proc ]]; then
        for entry in /proc/[0-9]*; do
            [[ -d "${entry}" ]] || continue
            pid="${entry#/proc/}"
            if is_server_pid "${pid}"; then
                printf '%s\n' "${pid}"
            fi
        done
        return 0
    fi
    ps -axww -o pid= | while IFS= read -r pid; do
        pid="${pid//[[:space:]]/}"
        if [[ -n "${pid}" ]] && is_server_pid "${pid}"; then
            printf '%s\n' "${pid}"
        fi
    done
}

wait_until_stopped() {
    local pid="$1"
    local seconds="$2"
    local i=0
    while [[ "${i}" -lt "${seconds}" ]]; do
        if ! is_server_pid "${pid}"; then
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    if ! is_server_pid "${pid}"; then
        return 0
    fi
    return 1
}

stop_one() {
    local pid="$1"
    echo "Stopping server ${pid} (SIGINT)."
    if ! is_server_pid "${pid}"; then
        echo "Server ${pid} stopped."
        return 0
    fi
    kill -INT "${pid}" 2>/dev/null || true
    if wait_until_stopped "${pid}" 30; then
        echo "Server ${pid} stopped."
        return 0
    fi
    echo "Server ${pid} is still running; sending SIGTERM." >&2
    kill -TERM "${pid}" 2>/dev/null || true
    if wait_until_stopped "${pid}" 10; then
        echo "Server ${pid} stopped."
        return 0
    fi
    echo "Server ${pid} is still running; sending SIGKILL." >&2
    kill -KILL "${pid}" 2>/dev/null || true
    if wait_until_stopped "${pid}" 5; then
        echo "Server ${pid} stopped."
        return 0
    fi
    echo "Server ${pid} did not exit." >&2
    return 1
}

pids=""
if [[ -f "${PID_FILE}" ]]; then
    recorded="$(tr -d '[:space:]' < "${PID_FILE}")"
    if [[ -n "${recorded}" ]] && is_server_pid "${recorded}"; then
        pids="${recorded}"
    fi
fi
if [[ -z "${pids}" ]]; then
    pids="$(find_server_pids | awk 'NF && !seen[$0]++')"
fi

if [[ -z "${pids}" ]]; then
    echo "Server is not running."
    rm -f "${PID_FILE}"
    exit 0
fi

status=0
pid=""
while IFS= read -r pid; do
    [[ -n "${pid}" ]] || continue
    if ! stop_one "${pid}"; then
        status=1
    fi
done <<< "${pids}"

if [[ "${status}" -eq 0 ]]; then
    rm -f "${PID_FILE}"
fi
exit "${status}"
