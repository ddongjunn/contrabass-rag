#!/usr/bin/env bash
# ragbot-server 배포/운영 스크립트 (봇 VM에서 바로 사용).
#   ./deploy.sh                # git pull → 빌드 + 기동(up -d --build) 후 헬스 대기  ← 기본
#   ./deploy.sh up             # pull 없이 빌드 + 기동
#   ./deploy.sh down           # 중지/제거
#   ./deploy.sh logs [svc]     # 로그 따라보기 (기본 app)
#   ./deploy.sh ps             # 상태
#   ./deploy.sh restart [svc]
#   ./deploy.sh <그 외>        # 정의되지 않은 인자는 docker compose 로 그대로 전달 (예: exec app sh, build, pull)
set -euo pipefail
cd "$(dirname "$0")"

# --- docker compose 명령 탐지 (v2 플러그인 우선, v1 폴백) ---
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  echo "[!] docker compose 를 찾을 수 없습니다. Docker를 설치/활성화하세요." >&2
  exit 1
fi

# --- 최신 소스 반영 (git 저장소일 때만) ---
git_update() {
  if ! command -v git >/dev/null 2>&1 || [ ! -d .git ]; then
    echo "[i] git 저장소가 아님 — pull 건너뜀"
    return 0
  fi
  echo "[*] 소스 업데이트: git pull --ff-only"
  git pull --ff-only
}

# --- 빌드 + 기동 + 헬스 대기 ---
up_and_wait() {
  if [ ! -f .env ]; then
    echo "[!] .env 가 없습니다. 먼저 생성하세요:" >&2
    echo "      cp .env.example .env && \${EDITOR:-vi} .env   # OPENAI_API_KEY 등 입력" >&2
    exit 1
  fi
  echo "[*] 빌드 및 기동: $COMPOSE up -d --build"
  $COMPOSE up -d --build
  echo "[*] 상태:"
  $COMPOSE ps
  # --- 앱 헬스 대기 (curl 있을 때만) ---
  if command -v curl >/dev/null 2>&1; then
    echo "[*] 헬스 대기: http://localhost:8080/actuator/health"
    for _ in $(seq 1 30); do
      if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
        echo "[✓] UP"
        curl -fsS http://localhost:8080/actuator/health; echo
        return 0
      fi
      sleep 2
    done
    echo "[!] 60초 내 UP 아님. 로그 확인: $COMPOSE logs -f app" >&2
    return 1
  else
    echo "[i] curl 미설치 — 헬스는 수동 확인: $COMPOSE logs -f app"
  fi
}

cmd="${1:-deploy}"
case "$cmd" in
  deploy)  git_update; up_and_wait ;;
  up)      up_and_wait ;;
  down)    $COMPOSE down ;;
  logs)    $COMPOSE logs -f "${2:-app}" ;;
  ps)      $COMPOSE ps ;;
  restart) $COMPOSE restart "${2:-}" ;;
  *)       $COMPOSE "$@" ;;   # 정의되지 않은 인자는 docker compose 로 그대로 전달
esac
