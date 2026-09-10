# Music Mirror

갤럭시에서 재생되는 **YouTube Music 청취 기록을 자동 수집**하고, 비공개 Cloudflare D1에 동기화하여 ChatGPT가 MCP 도구로 분석할 수 있게 하는 개인용 프로젝트입니다.

## 현재 구현 범위

- Android 8.0 이상, YouTube Music 앱 전용
- 곡명, 아티스트, 앨범, 재생 길이
- 재생 시작/종료, 일시정지, 스킵, 탐색, 반복
- 실제 청취 시간, 마지막 재생 위치, 80% 이상 완주 여부
- 인터넷이 없어도 SQLite에 먼저 저장하고 나중에 자동 동기화
- 서버 전송용 토큰과 ChatGPT 조회용 토큰 분리
- Android의 전송용 토큰은 Android Keystore로 암호화
- ChatGPT용 읽기 전용 도구 6개

검색어, 좋아요 버튼, 자동재생/플레이리스트 출처는 YouTube Music이 Android 미디어 세션으로 제공하지 않을 수 있으므로 현재 수집하지 않습니다. Google 로그인 정보와 YouTube 인증 토큰은 사용하지 않습니다.

## 폴더

- `android/` — Android Studio 프로젝트
- `worker/` — Cloudflare Worker, D1 스키마, MCP 서버
- `.codex-plugin/`, `.mcp.json` — 개인 ChatGPT/Codex 플러그인 메타데이터
- `docs/SETUP_KO.md` — 처음 설치하는 순서
- `docs/PRIVACY_KO.md` — 저장 정보와 보안 경계

## ChatGPT에서 가능한 질문

- “최근 7일 동안 뭘 가장 많이 들었어?”
- “새벽과 낮의 음악 취향 차이를 분석해줘.”
- “스킵하지 않고 끝까지 듣는 곡들의 공통점은?”
- “지난달보다 이번 달에 자주 듣게 된 아티스트는?”
- “지금 무슨 노래를 듣고 있어?”

## 빠른 확인

서버 단위 테스트는 외부 패키지 없이 실행됩니다.

```bash
cd worker
node --test
```

Android APK 빌드에는 Android Studio와 Android SDK 35가 필요합니다. 자세한 순서는 [설치 안내](docs/SETUP_KO.md)를 따르세요.

Android Studio를 사용할 수 없다면 GitHub에 이 프로젝트를 올린 뒤 **Actions → Build Music Mirror APK → Run workflow**를 누르세요. 빌드가 끝나면 `Music-Mirror-debug` 아티팩트에서 APK를 받을 수 있습니다.
