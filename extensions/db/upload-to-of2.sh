#!/usr/bin/env bash

# Upload this checkout from the local machine to of2.
# Run it on the laptop, then build on of2 with build-on-of2.sh.
# Build outputs stay on of2: rsync does not send or delete them.

set -o errexit
set -o pipefail
set -o nounset

usage() {
    cat <<'EOF'
Usage: upload-to-of2.sh [--dry-run]

  rsync this repo to of2:/home/rzhao/workspace/deephaven-core-faithray

Environment:
  OF2_HOST   SSH host. Default: of2
  OF2_DEST   Remote checkout. Default: /home/rzhao/workspace/deephaven-core-faithray
EOF
}

RSYNC_ARGS=(-az --delete)
DRY_RUN=0
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi
if [[ "${1:-}" == "--dry-run" ]]; then
    RSYNC_ARGS+=(--dry-run)
    DRY_RUN=1
    shift
fi
if [[ $# -ne 0 ]]; then
    usage
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OF2_HOST="${OF2_HOST:-of2}"
OF2_DEST="${OF2_DEST:-/home/rzhao/workspace/deephaven-core-faithray}"

if ! command -v rsync >/dev/null 2>&1; then
    echo "rsync is required." >&2
    exit 1
fi

echo "From: ${REPO_ROOT}/"
echo "To:   ${OF2_HOST}:${OF2_DEST}/"

ssh "${OF2_HOST}" "mkdir -p '${OF2_DEST}'"

rsync "${RSYNC_ARGS[@]}" \
    --exclude '.git/' \
    --exclude '.gradle/' \
    --exclude '.idea/' \
    --exclude '.vscode/' \
    --exclude '.venv/' \
    --exclude 'build/' \
    --exclude 'buildTest/' \
    --exclude 'out/' \
    --exclude 'node_modules/' \
    --exclude 'web/.cache/' \
    --exclude 'py/venvs/' \
    --exclude 'py/installs/' \
    --exclude '__pycache__/' \
    --exclude '*.pyc' \
    --exclude '.DS_Store' \
    --exclude '*.iml' \
    "${REPO_ROOT}/" "${OF2_HOST}:${OF2_DEST}/"

if [[ "${DRY_RUN}" == "1" ]]; then
    echo "Dry run only. Nothing was uploaded."
    exit 0
fi

echo "Uploaded. On of2:"
echo "  cd ${OF2_DEST} && extensions/db/build-on-of2.sh"
