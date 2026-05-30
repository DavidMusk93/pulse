#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEV_DIR="${ROOT_DIR}/.dev"

log() {
  printf '[pulse-dev] %s\n' "$1"
}

fail() {
  printf '[pulse-dev] ERROR: %s\n' "$1" >&2
  exit 1
}

log "checking local development environment without sudo"

command -v java >/dev/null 2>&1 || fail "java is required. Install JDK 17 locally and retry."
command -v mvn >/dev/null 2>&1 || fail "maven is required. Install Maven locally and retry."

JAVA_VERSION_OUTPUT="$(java -version 2>&1 | head -n 1)"
case "${JAVA_VERSION_OUTPUT}" in
  *\"17\"*|*\"18\"*|*\"19\"*|*\"20\"*|*\"21\"*|*\"22\"*|*\"23\"*|*\"24\"*|*\"25\"*)
    log "java version ok: ${JAVA_VERSION_OUTPUT}"
    ;;
  *)
    fail "JDK 17+ is required, current: ${JAVA_VERSION_OUTPUT}"
    ;;
esac

mkdir -p "${DEV_DIR}/logs" "${DEV_DIR}/tmp"

export MAVEN_OPTS="${MAVEN_OPTS:-} -Djava.io.tmpdir=${DEV_DIR}/tmp"

log "running tests"
(cd "${ROOT_DIR}" && mvn test)

log "environment is ready"
log "start coordinator with: mvn package && java -jar target/pulse-0.1.0-SNAPSHOT.jar"
