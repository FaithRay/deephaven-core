#!/usr/bin/env bash

# Build the Groovy server distribution on of2.
# JDK 21 runs Gradle. Docker builds the web IDE and the protobuf stubs.
# of2's Docker bridge cannot resolve registry.npmjs.org, so image builds use
# the host network.
#
# The offline box does not run this script. Pack the result with pack-on-of2.sh.

set -o errexit
set -o pipefail
set -o nounset

usage() {
    cat <<'EOF'
Usage: build-on-of2.sh

  Build server/jetty-app/build/distributions/server-jetty-*.tar
  (Groovy console, web IDE, and extensions/db).

Environment:
  JAVA_HOME   JDK 21. Default: /home/rzhao/jdks/openjdk-21
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

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

if ! command -v docker >/dev/null 2>&1; then
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
./gradlew server-jetty-app:distTar -Pgroovy --init-script "${INIT_SCRIPT}" --console=plain

newest=""
shopt -s nullglob
for tarfile in server/jetty-app/build/distributions/server-jetty-*.tar; do
    if [[ -z "${newest}" || "${tarfile}" -nt "${newest}" ]]; then
        newest="${tarfile}"
    fi
done
shopt -u nullglob

if [[ -z "${newest}" ]]; then
    echo "distTar finished but no server-jetty-*.tar was produced." >&2
    exit 1
fi

echo "Distribution: ${REPO_ROOT}/${newest}"
echo "Next: ${SCRIPT_DIR}/pack-on-of2.sh"
