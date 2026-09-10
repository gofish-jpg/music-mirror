# Music Mirror DJ v0.2 업그레이드

v0.2는 기존 수집·분석 기능에 다음 흐름을 추가합니다.

1. ChatGPT가 청취 기록과 기존 피드백을 분석합니다.
2. ChatGPT가 `save_recommendation_mix` MCP 도구로 추천 믹스를 저장합니다.
3. Android 앱이 추천 믹스와 최근 30일 요약을 불러옵니다.
4. 사용자는 추천 이유를 보고 YouTube Music에서 곡을 열거나 좋아요·별로 피드백을 남깁니다.

OpenAI API 키는 필요하지 않습니다. 기존 앱 서버 주소와 `INGEST_TOKEN`, ChatGPT 플러그인 주소는 그대로 사용합니다.

## 1. 기존 D1 데이터베이스 확장

Cloudflare D1의 `music-mirror` 데이터베이스 콘솔에서 `worker/migrations/0002_ai_dj.sql` 전체를 한 번 실행합니다. 기존 청취 기록은 삭제하거나 변경하지 않습니다.

## 2. Worker 배포

Cloudflare Worker 편집기의 `worker.js` 내용을 `worker/src/worker.mjs`로 교체하고 배포합니다. `/health` 응답의 버전이 `0.2.0`이면 새 코드가 적용된 것입니다.

## 3. Android APK 빌드

GitHub 저장소에 v0.2 파일을 반영하면 `android/**` 변경으로 GitHub Actions 빌드가 자동 시작됩니다. Actions의 `Music-Mirror-DJ-v0.2-debug` 아티팩트에서 APK를 받습니다.

새 워크플로는 디버그 서명 키를 GitHub Actions 캐시에 보존합니다. 기존 v0.1 APK와 첫 v0.2 APK의 서명이 다르면 v0.1을 삭제한 뒤 v0.2를 설치해야 할 수 있습니다. 삭제 전 반드시 앱에서 `저장하고 지금 동기화`를 눌러 로컬 기록을 서버에 전송합니다.

## 4. 첫 AI 믹스

ChatGPT의 새 대화에서 Music Mirror를 켜고 다음처럼 요청합니다.

> 최근 30일 청취 기록과 추천 피드백을 분석해줘. 반복 재생, 완주율, 청취 시간, 스킵을 반영해서 새로운 곡 중심의 추천 믹스 10곡을 만들고 Music Mirror 앱에 저장해줘. 각 곡의 추천 이유도 포함해줘.

저장이 끝나면 Android 앱에서 `AI 추천 새로고침`을 누릅니다.
