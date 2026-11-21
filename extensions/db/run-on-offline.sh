#!/usr/bin/env bash

# Start the Groovy server from an archive produced by pack-on-of2.sh.
# Unpack first, then run this script from that directory:
#   mkdir -p ~/deephaven && tar -xzf deephaven-offline.tar.gz -C ~/deephaven
#   ~/deephaven/run-on-offline.sh
#   ~/deephaven/stop-on-offline.sh
# The archive ships its own JDK. No Gradle, Docker, Python, or network.

set -o errexit
set -o pipefail
set -o nounset

usage() {
    cat <<'EOF'
Usage: run-on-offline.sh

  Start the packed Groovy server on port 10000.
  Ctrl+C stops a server in this terminal. stop-on-offline.sh stops
  one that was left running.

Environment:
  JAVA_HOME       Default: <this directory>/jdk
  DATABASE_ROOT   db.t / db.i files. Default: <this directory>/deephaven-db
                  when that directory was packed, otherwise ~/deephaven-db
  PSK             IDE pre-shared key. Default: deephaven
  START_OPTS      Extra server JVM arguments. Heap, Database.root, the
                  pre-shared key, and the Groovy console are set when this
                  is unset.
  APPLICATION_DIR Groovy application directory. When set, added to
                  START_OPTS as -Ddeephaven.application.dir.
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
export JAVA_HOME="${JAVA_HOME:-${BUNDLE_DIR}/jdk}"
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ ! -x "${BUNDLE_DIR}/server-jetty/bin/start" ]]; then
    echo "Server start script not found at ${BUNDLE_DIR}/server-jetty/bin/start" >&2
    echo "Unpack deephaven-offline.tar.gz and run the copy of this script inside it." >&2
    exit 1
fi

if [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "JDK 21 not found at ${JAVA_HOME}" >&2
    exit 1
fi

JAVA_VERSION="$("${JAVA_HOME}/bin/java" -version 2>&1 | awk -F '[".]' '/version/ {print $2}')"
if [[ "${JAVA_VERSION}" != "21" ]]; then
    echo "JAVA_HOME must be JDK 21, found version ${JAVA_VERSION} at ${JAVA_HOME}" >&2
    exit 1
fi

if [[ -d "${BUNDLE_DIR}/deephaven-db" ]]; then
    export DATABASE_ROOT="${DATABASE_ROOT:-${BUNDLE_DIR}/deephaven-db}"
else
    export DATABASE_ROOT="${DATABASE_ROOT:-${HOME}/deephaven-db}"
fi
export PSK="${PSK:-deephaven}"
export START_OPTS="${START_OPTS:--Xmx4g -DDatabase.root=${DATABASE_ROOT} -Dauthentication.psk=${PSK} -Ddeephaven.console.type=groovy}"
if [[ -n "${APPLICATION_DIR:-}" ]]; then
    export START_OPTS="${START_OPTS} -Ddeephaven.application.dir=${APPLICATION_DIR}"
fi
mkdir -p "${DATABASE_ROOT}"

pid_is_this_server() {
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
        *"io.deephaven.server.jetty.JettyMain"*|*"${BUNDLE_DIR}/server-jetty"*|*"${BUNDLE_DIR}/jdk/bin/java "*)
            return 0
            ;;
    esac
    return 1
}

PID_FILE="${BUNDLE_DIR}/run-on-offline.pid"
if [[ -f "${PID_FILE}" ]]; then
    existing="$(tr -d '[:space:]' < "${PID_FILE}")"
    if pid_is_this_server "${existing}"; then
        echo "Server is already running (pid ${existing})." >&2
        echo "Stop it with ${BUNDLE_DIR}/stop-on-offline.sh" >&2
        exit 1
    fi
fi
printf '%s\n' "$$" > "${PID_FILE}"
# exec replaces this shell and drops the trap. A failed exec still removes the pid file.
trap 'rm -f "${PID_FILE}"' EXIT

echo "Groovy IDE: http://localhost:10000/?psk=${PSK}"
echo "Database root: ${DATABASE_ROOT}"
if [[ -n "${APPLICATION_DIR:-}" ]]; then
    echo "Application directory: ${APPLICATION_DIR}"
fi
exec "${BUNDLE_DIR}/server-jetty/bin/start"
