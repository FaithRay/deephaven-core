#!/usr/bin/env bash

# Build or start this checkout on of2.
# JDK 21 runs Gradle. Docker builds the web IDE and the protobuf stubs.
# of2's Docker bridge cannot resolve registry.npmjs.org, so image builds use
# the host network.

set -o errexit
set -o pipefail
set -o nounset

usage() {
    cat <<'EOF'
Usage: build-of2.sh <test|server>

  test    Compile and run :extensions-db:test
  server  Build the Groovy server and leave it running on port 10000

Environment:
  JAVA_HOME      JDK 21. Default: /home/rzhao/jdks/openjdk-21
  DATABASE_ROOT  db.t / db.i files. Default: /home/rzhao/deephaven-db
  PSK            IDE pre-shared key. Default: deephaven
  START_OPTS     Extra server JVM arguments. Heap and Database.root are set
                 when this is unset.
  APPLICATION_DIR  Groovy application directory. When set, added to
                   START_OPTS as -Ddeephaven.application.dir.
EOF
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MODE="${1:-}"

if [[ "${MODE}" != "test" && "${MODE}" != "server" ]]; then
    usage
    exit 2
fi

export JAVA_HOME="${JAVA_HOME:-/home/rzhao/jdks/openjdk-21}"
export PATH="${JAVA_HOME}/bin:${PATH}"

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

if [[ "${MODE}" == "server" ]] && ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required to package the web IDE and generate protobuf code." >&2
    exit 1
fi

INIT_SCRIPT="$(mktemp)"
trap 'rm -f "${INIT_SCRIPT}"' EXIT
cat > "${INIT_SCRIPT}" <<'EOF'
gradle.projectsEvaluated {
    rootProject.allprojects {
        tasks.configureEach { task ->
            if (task.class.name.contains("DockerBuildImage")) {
                task.network.set("host")
            }
        }
    }
}
EOF

cd "${REPO_ROOT}"

if [[ "${MODE}" == "test" ]]; then
    exec ./gradlew :extensions-db:test --init-script "${INIT_SCRIPT}" --console=plain
fi

export DATABASE_ROOT="${DATABASE_ROOT:-/home/rzhao/deephaven-db}"
export PSK="${PSK:-deephaven}"
export START_OPTS="${START_OPTS:--Xmx4g -DDatabase.root=${DATABASE_ROOT}}"
if [[ -n "${APPLICATION_DIR:-}" ]]; then
    export START_OPTS="${START_OPTS} -Ddeephaven.application.dir=${APPLICATION_DIR}"
fi
mkdir -p "${DATABASE_ROOT}"

echo "Groovy IDE: http://localhost:10000/?psk=${PSK}"
echo "Database root: ${DATABASE_ROOT}"
if [[ -n "${APPLICATION_DIR:-}" ]]; then
    echo "Application directory: ${APPLICATION_DIR}"
fi
exec ./gradlew server-jetty-app:run -Pgroovy "-Ppsk=${PSK}" --init-script "${INIT_SCRIPT}" --console=plain
