#!/usr/bin/env bash
# V월드 내장 키를 git 밖(측량 앱 작업 폴더)에서 복사해 넣는다. 결과 파일은 .gitignore 대상 — 절대 커밋하지 않는다.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="${VWORLD_DEFAULTS_SRC:-/workspace/survey-app/app-0132/src/main/kotlin/kr/archsurvey/map/VWorldDefaults.kt}"
DST=src/main/kotlin/kr/archsurvey/map/VWorldDefaults.kt
[ -f "$SRC" ] || { echo "키 원본 없음: $SRC" >&2; exit 1; }
grep -q 'BUILTIN_API_KEY = "[A-Za-z0-9-]\{20,\}"' "$SRC" || { echo "원본에 키가 비어 있음" >&2; exit 1; }
cp "$SRC" "$DST"
git check-ignore -q "$DST" || { echo "경고: $DST 가 git 무시 대상이 아님" >&2; exit 1; }
echo "V월드 키 넣음 (끝 4자리 $(grep -o 'BUILTIN_API_KEY = "[^"]*"' "$DST" | cut -d'"' -f2 | tail -c 5))"
