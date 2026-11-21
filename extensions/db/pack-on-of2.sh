#!/usr/bin/env bash

# Pack the of2 server distribution and JDK 21 into one archive for an offline box.
# Run build-on-of2.sh first. The archive contains server-jetty/, jdk/, and
# run-on-offline.sh and stop-on-offline.sh. It does not need Gradle, Docker,
# or this git checkout.

set -o errexit
set -o pipefail
set -o nounset

usage() {
    cat <<'EOF'
Usage: pack-on-of2.sh [output.tar.gz]

  Pack the newest server-jetty-*.tar plus JAVA_HOME.
  Default output: ~/deephaven-offline.tar.gz

Environment:
  JAVA_HOME           JDK 21 copied into the archive.
                      Default: /home/rzhao/jdks/openjdk-21
  INCLUDE_DATABASE   Set to 1 to also pack DATABASE_ROOT as deephaven-db/.
  DATABASE_ROOT       db.t / db.i files. Default: /home/rzhao/deephaven-db
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi
if [[ $# -gt 1 ]]; then
    usage
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT="${1:-${HOME}/deephaven-offline.tar.gz}"
export JAVA_HOME="${JAVA_HOME:-/home/rzhao/jdks/openjdk-21}"

if [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "JDK 21 not found at ${JAVA_HOME}" >&2
    echo "Install Temurin 21 and set JAVA_HOME. See build-of2.md." >&2
    exit 1
fi

JAVA_VERSION="$("${JAVA_HOME}/bin/java" -version 2>&1 | awk -F '[".]' '/version/ {print $2}')"
if [[ "${JAVA_VERSION}" != "21" ]]; then
    echo "JAVA_HOME must be JDK 21, found version ${JAVA_VERSION} at ${JAVA_HOME}" >&2
    exit 1
fi

JDK_REAL="$(cd "${JAVA_HOME}" && pwd -P)"

newest=""
shopt -s nullglob
for tarfile in "${REPO_ROOT}"/server/jetty-app/build/distributions/server-jetty-*.tar; do
    if [[ -z "${newest}" || "${tarfile}" -nt "${newest}" ]]; then
        newest="${tarfile}"
    fi
done
shopt -u nullglob

if [[ -z "${newest}" ]]; then
    echo "No server distribution under server/jetty-app/build/distributions/." >&2
    echo "Run ${SCRIPT_DIR}/build-on-of2.sh first." >&2
    exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT

echo "Server distribution: ${newest}"
echo "JDK: ${JDK_REAL}"

tar -C "${STAGE}" -xf "${newest}"
shopt -s nullglob
extracted=("${STAGE}"/server-jetty-*)
shopt -u nullglob
if [[ ${#extracted[@]} -ne 1 || ! -d "${extracted[0]}" ]]; then
    echo "Expected one server-jetty-* directory inside ${newest}" >&2
    exit 1
fi
mv "${extracted[0]}" "${STAGE}/server-jetty"

mkdir -p "${STAGE}/jdk"
cp -a "${JDK_REAL}/." "${STAGE}/jdk/"
cp "${SCRIPT_DIR}/run-on-offline.sh" "${STAGE}/run-on-offline.sh"
cp "${SCRIPT_DIR}/stop-on-offline.sh" "${STAGE}/stop-on-offline.sh"
chmod +x "${STAGE}/run-on-offline.sh" "${STAGE}/stop-on-offline.sh"

members=(server-jetty jdk run-on-offline.sh stop-on-offline.sh)
if [[ "${INCLUDE_DATABASE:-}" == "1" ]]; then
    DATABASE_ROOT="${DATABASE_ROOT:-/home/rzhao/deephaven-db}"
    if [[ ! -d "${DATABASE_ROOT}" ]]; then
        echo "INCLUDE_DATABASE=1 but ${DATABASE_ROOT} is not a directory." >&2
        exit 1
    fi
    echo "Database root: ${DATABASE_ROOT}"
    mkdir -p "${STAGE}/deephaven-db"
    cp -a "${DATABASE_ROOT}/." "${STAGE}/deephaven-db/"
    members+=(deephaven-db)
fi

mkdir -p "$(dirname "${OUTPUT}")"
tar -C "${STAGE}" -czf "${OUTPUT}" "${members[@]}"

echo "Packed: ${OUTPUT}"
echo "Copy that file to the offline box, then:"
echo "  mkdir -p ~/deephaven && tar -xzf deephaven-offline.tar.gz -C ~/deephaven"
echo "  ~/deephaven/run-on-offline.sh"
echo "  ~/deephaven/stop-on-offline.sh"
