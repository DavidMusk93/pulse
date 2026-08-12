#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

SOURCE="${SOURCE:-https://github.com/EveryInc/compound-engineering-plugin}"
REF="${REF:-main}"
AGENT="${AGENT:-trae-cn}"
APPLY=0
COPY_MODE=1
EVIDENCE_DIR=""

usage() {
  cat <<'USAGE'
Usage:
  docs/script/sync-trae-skills.sh [--apply] [--source <github-url-or-owner/repo>] [--ref <ref>] [--agent <agent>] [--evidence-dir <path>]

Defaults:
  --source https://github.com/EveryInc/compound-engineering-plugin
  --ref main
  --agent trae-cn

Behavior:
  Without --apply, download the source archive, list available skills, and write evidence only.
  With --apply, compare each discovered skill against the installed directory and install only
  missing or changed skills with:
    npx skills@latest add <local-root> --skill <name> ... --global --yes --agent <agent> --copy

Safety contract:
  - Downloads and evidence stay under .tmp/ by default.
  - Installation uses copy mode so installed skills do not depend on .tmp paths.
  - Unchanged installed skills are skipped, avoiding needless overwrite/unlink.
  - The script verifies installed skill directories with diff -qr after apply.
  - The script never deletes installed skills that disappeared upstream.

Examples:
  docs/script/sync-trae-skills.sh
  docs/script/sync-trae-skills.sh --apply
  docs/script/sync-trae-skills.sh --apply --source emilkowalski/skills --ref main
USAGE
}

log() {
  printf '[skill-sync] %s\n' "$1"
}

fail() {
  printf '[skill-sync] ERROR: %s\n' "$1" >&2
  exit 1
}

sha256_file() {
  local file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

sanitize() {
  printf '%s' "$1" | tr '/:@ ' '----' | tr -cd 'A-Za-z0-9._-'
}

parse_github_source() {
  local source=$1
  local stripped

  stripped="${source#https://github.com/}"
  stripped="${stripped#http://github.com/}"
  stripped="${stripped#github.com/}"
  stripped="${stripped#github:}"
  stripped="${stripped%.git}"

  if [[ ! "$stripped" =~ ^[^/]+/[^/]+$ ]]; then
    fail "source must be a GitHub repo URL or owner/repo: $source"
  fi

  OWNER="${stripped%%/*}"
  REPO="${stripped#*/}"
}

installed_base_dir() {
  case "$AGENT" in
    trae-cn)
      printf '%s/.trae-cn/skills' "$HOME"
      ;;
    trae)
      printf '%s/.trae/skills' "$HOME"
      ;;
    *)
      fail "post-install diff verification is currently implemented for trae-cn and trae only, got: $AGENT"
      ;;
  esac
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apply)
      APPLY=1
      shift
      ;;
    --source)
      [ "$#" -ge 2 ] || fail "--source requires a value"
      SOURCE=$2
      shift 2
      ;;
    --ref)
      [ "$#" -ge 2 ] || fail "--ref requires a value"
      REF=$2
      shift 2
      ;;
    --agent)
      [ "$#" -ge 2 ] || fail "--agent requires a value"
      AGENT=$2
      shift 2
      ;;
    --evidence-dir)
      [ "$#" -ge 2 ] || fail "--evidence-dir requires a value"
      EVIDENCE_DIR=$2
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v tar >/dev/null 2>&1 || fail "tar is required"
command -v npx >/dev/null 2>&1 || fail "npx is required"
command -v diff >/dev/null 2>&1 || fail "diff is required"

parse_github_source "$SOURCE"

STAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
SAFE_SOURCE="$(sanitize "${OWNER}-${REPO}")"
SAFE_REF="$(sanitize "$REF")"
if [ -z "$EVIDENCE_DIR" ]; then
  EVIDENCE_DIR="${ROOT_DIR}/.tmp/data/skill-sync/${SAFE_SOURCE}/${SAFE_REF}/${STAMP}"
elif [[ "$EVIDENCE_DIR" != /* ]]; then
  EVIDENCE_DIR="${ROOT_DIR}/${EVIDENCE_DIR}"
fi

ARCHIVE_URL="https://codeload.github.com/${OWNER}/${REPO}/tar.gz/${REF}"
ARCHIVE_PATH="${EVIDENCE_DIR}/repo.tar.gz"
EXTRACT_DIR="${EVIDENCE_DIR}/extract"
SKILL_LIST="${EVIDENCE_DIR}/skills.txt"
LIST_LOG="${EVIDENCE_DIR}/skills-list-source.log"
INSTALL_LOG="${EVIDENCE_DIR}/install.log"
VERIFY_LOG="${EVIDENCE_DIR}/verify.log"
INSTALLED_LIST_LOG="${EVIDENCE_DIR}/skills-list-installed.log"
SYNC_PLAN="${EVIDENCE_DIR}/sync-plan.tsv"
MANIFEST="${EVIDENCE_DIR}/manifest.md"

mkdir -p "$EVIDENCE_DIR" "$EXTRACT_DIR"

log "source=${SOURCE} ref=${REF} agent=${AGENT} apply=${APPLY}"
log "evidence=${EVIDENCE_DIR}"
log "downloading ${ARCHIVE_URL}"
curl -L --max-time 120 --fail --silent --show-error -o "$ARCHIVE_PATH" "$ARCHIVE_URL"
ARCHIVE_SHA="$(sha256_file "$ARCHIVE_PATH")"
log "archive_sha256=${ARCHIVE_SHA}"

tar -xzf "$ARCHIVE_PATH" -C "$EXTRACT_DIR"
EXTRACTED_ROOT="$(find "$EXTRACT_DIR" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
[ -n "$EXTRACTED_ROOT" ] || fail "archive did not extract a root directory"
[ -d "${EXTRACTED_ROOT}/skills" ] || fail "missing skills directory under extracted root: ${EXTRACTED_ROOT}"

find "${EXTRACTED_ROOT}/skills" -mindepth 2 -maxdepth 2 -type f -name SKILL.md \
  | sed "s#${EXTRACTED_ROOT}/skills/##; s#/SKILL.md##" \
  | sort > "$SKILL_LIST"

SKILL_COUNT="$(wc -l < "$SKILL_LIST" | tr -d ' ')"
[ "$SKILL_COUNT" -gt 0 ] || fail "no skills found under ${EXTRACTED_ROOT}/skills"
log "skill_count=${SKILL_COUNT}"

{
  printf '# Skill Sync Manifest\n\n'
  printf -- '- source: `%s`\n' "$SOURCE"
  printf -- '- owner: `%s`\n' "$OWNER"
  printf -- '- repo: `%s`\n' "$REPO"
  printf -- '- ref: `%s`\n' "$REF"
  printf -- '- archive_url: `%s`\n' "$ARCHIVE_URL"
  printf -- '- archive_sha256: `%s`\n' "$ARCHIVE_SHA"
  printf -- '- extracted_root: `%s`\n' "$EXTRACTED_ROOT"
  printf -- '- agent: `%s`\n' "$AGENT"
  printf -- '- scope: `global`\n'
  printf -- '- mode: `copy`\n'
  printf -- '- apply: `%s`\n' "$APPLY"
  printf -- '- generated_at_utc: `%s`\n\n' "$STAMP"
  printf '## Skills\n\n'
  sed 's/^/- `/' "$SKILL_LIST" | sed 's/$/`/'
} > "$MANIFEST"

log "listing source skills with skills CLI"
npx skills@latest add "$EXTRACTED_ROOT" --list | tee "$LIST_LOG"

BASE_DIR="$(installed_base_dir)"
: > "$SYNC_PLAN"
INSTALL_SKILLS=()
while IFS= read -r skill; do
  src_dir="${EXTRACTED_ROOT}/skills/${skill}"
  dst_dir="${BASE_DIR}/${skill}"
  if [ ! -d "$dst_dir" ]; then
    printf '%s\tmissing\t%s\n' "$skill" "$dst_dir" >> "$SYNC_PLAN"
    INSTALL_SKILLS+=("$skill")
  elif diff -qr "$src_dir" "$dst_dir" >/dev/null 2>&1; then
    printf '%s\tunchanged\t%s\n' "$skill" "$dst_dir" >> "$SYNC_PLAN"
  else
    printf '%s\tchanged\t%s\n' "$skill" "$dst_dir" >> "$SYNC_PLAN"
    INSTALL_SKILLS+=("$skill")
  fi
done < "$SKILL_LIST"

UNCHANGED_COUNT="$(awk -F '\t' '$2 == "unchanged" {count++} END {print count + 0}' "$SYNC_PLAN")"
MISSING_COUNT="$(awk -F '\t' '$2 == "missing" {count++} END {print count + 0}' "$SYNC_PLAN")"
CHANGED_COUNT="$(awk -F '\t' '$2 == "changed" {count++} END {print count + 0}' "$SYNC_PLAN")"
log "sync_plan unchanged=${UNCHANGED_COUNT} missing=${MISSING_COUNT} changed=${CHANGED_COUNT}"

if [ "$APPLY" -ne 1 ]; then
  log "dry run complete; rerun with --apply to install"
  log "manifest=${MANIFEST}"
  log "sync_plan=${SYNC_PLAN}"
  exit 0
fi

if [ "$COPY_MODE" -ne 1 ]; then
  fail "only copy mode is supported"
fi

if [ "${#INSTALL_SKILLS[@]}" -eq 0 ]; then
  log "no missing or changed skills; skipping install"
  : > "$INSTALL_LOG"
else
  log "installing ${#INSTALL_SKILLS[@]} missing/changed skills"
  INSTALL_ARGS=()
  for skill in "${INSTALL_SKILLS[@]}"; do
    INSTALL_ARGS+=(--skill "$skill")
  done
  npx skills@latest add "$EXTRACTED_ROOT" "${INSTALL_ARGS[@]}" --global --yes --agent "$AGENT" --copy | tee "$INSTALL_LOG"
fi

[ -d "$BASE_DIR" ] || fail "installed skills base is missing: $BASE_DIR"

log "verifying installed directories with diff -qr"
: > "$VERIFY_LOG"
while IFS= read -r skill; do
  src_dir="${EXTRACTED_ROOT}/skills/${skill}"
  dst_dir="${BASE_DIR}/${skill}"
  if [ ! -d "$dst_dir" ]; then
    printf 'MISSING %s %s\n' "$skill" "$dst_dir" | tee -a "$VERIFY_LOG"
    exit 1
  fi
  if diff -qr "$src_dir" "$dst_dir" >> "$VERIFY_LOG" 2>&1; then
    printf 'MATCH %s\n' "$skill" | tee -a "$VERIFY_LOG"
  else
    printf 'MISMATCH %s\n' "$skill" | tee -a "$VERIFY_LOG"
    exit 1
  fi
done < "$SKILL_LIST"

log "listing installed skills"
npx skills@latest list --global --agent "$AGENT" | tee "$INSTALLED_LIST_LOG"

log "sync complete"
log "manifest=${MANIFEST}"
