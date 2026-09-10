# Music Mirror 설치 안내

완성 후에는 갤럭시만 켜져 있으면 기록과 동기화가 진행됩니다. 아래의 최초 설정에만 PC가 필요합니다.

## 1. 비밀 키 두 개 만들기

Windows PowerShell에서 프로젝트의 `scripts` 폴더로 이동한 뒤 실행합니다.

```powershell
.\New-MusicMirrorSecrets.ps1
```

표시되는 값을 각각 안전한 곳에 잠시 보관합니다.

- `INGEST_TOKEN`: 갤럭시 앱이 기록을 전송할 때만 사용
- `READ_TOKEN`: ChatGPT가 기록을 읽을 때만 사용

두 값은 서로 다르게 유지하세요. 채팅이나 공개 저장소에 올리지 마세요.

## 2. Cloudflare Worker와 D1 만들기

Node.js 설치 후 `worker` 폴더에서 다음을 실행합니다.

```powershell
npm install
npx wrangler login
npx wrangler d1 create music-mirror
```

마지막 명령이 출력한 `database_id`를 `worker/wrangler.toml`의 `REPLACE_WITH_D1_DATABASE_ID` 대신 넣습니다.

DB 테이블을 만들고 비밀 키를 등록합니다.

```powershell
npx wrangler d1 execute music-mirror --remote --file=schema.sql
npx wrangler secret put INGEST_TOKEN
npx wrangler secret put READ_TOKEN
npm run deploy
```

각 `secret put` 명령이 물어볼 때 1단계에서 만든 대응 값을 붙여 넣습니다. 배포가 끝나면 `https://music-mirror.<계정>.workers.dev` 형태의 주소가 표시됩니다.

브라우저에서 `<Worker 주소>/health`를 열어 아래와 비슷한 응답이 보이는지 확인합니다.

```json
{"ok":true,"service":"music-mirror","version":"0.1.0"}
```

## 3. Android 앱 빌드 및 설치

### 방법 A: GitHub에서 자동 빌드

Android Studio를 지금 사용할 수 없다면 이 방법을 사용합니다.

1. 프로젝트를 GitHub 비공개 저장소에 올립니다.
2. 저장소의 `Actions` 탭을 엽니다.
3. `Build Music Mirror APK`를 선택하고 `Run workflow`를 누릅니다.
4. 완료된 실행의 `Artifacts`에서 `Music-Mirror-debug`를 받습니다.
5. ZIP 안의 `app-debug.apk`를 갤럭시에서 설치합니다.

워크플로는 Java 17, Android SDK 35, Gradle 8.11.1을 자동으로 준비합니다.

### 방법 B: Android Studio에서 빌드

1. Android Studio에서 `android` 폴더를 엽니다.
2. SDK 35 설치 안내가 나오면 설치합니다.
3. 갤럭시에서 개발자 옵션과 USB 디버깅을 켭니다.
4. USB로 연결하고 Android Studio의 Run 버튼을 누릅니다.
5. 또는 `Build > Build APK(s)`로 APK를 만든 뒤 폰에 설치합니다.

앱을 처음 실행한 뒤:

1. **알림 접근 권한 열기**를 누릅니다.
2. 목록에서 **Music Mirror**만 허용합니다.
3. 개인 서버 주소에 2단계의 Worker 주소를 입력합니다. `/mcp`나 `/v1/sync`는 붙이지 않습니다.
4. 수집용 비밀 키에 `INGEST_TOKEN`을 입력합니다.
5. **저장하고 지금 동기화**를 누릅니다.
6. YouTube Music에서 한 곡을 재생하고 앱의 저장 세션 수가 증가하는지 확인합니다.

삼성 배터리 절전으로 수집이 끊기면 `설정 > 배터리 > 백그라운드 사용 제한 > 절전 예외 앱`에 Music Mirror를 추가하세요.

## 4. ChatGPT에 연결

프로젝트 루트의 `.mcp.json`에서 다음 두 값만 교체합니다.

- `https://YOUR-WORKER.workers.dev/mcp` → 실제 Worker 주소 뒤에 `/mcp`
- `YOUR_READ_TOKEN` → 1단계에서 만든 `READ_TOKEN`

ChatGPT의 개발자 모드가 제공되는 계정에서는 설정의 Plugins에서 새 연결을 만들고 MCP URL을 등록할 수 있습니다. 연결 방식에서 인증 헤더를 지원하면 `Authorization: Bearer <READ_TOKEN>`을 사용합니다.

Codex 개인 플러그인으로 사용할 때는 이 프로젝트의 `music-mirror` 플러그인을 개인 마켓플레이스에 추가한 뒤 새 대화에서 활성화합니다.

## 5. 삭제와 백업

- 폰의 로컬 기록 삭제: Music Mirror 앱 정보에서 저장공간 데이터 삭제
- 서버 기록 백업: `GET <Worker 주소>/v1/export?days=365`에 `Authorization: Bearer <READ_TOKEN>` 헤더 사용
- 서버 전체 삭제: Cloudflare D1의 `music-mirror` 데이터베이스 삭제
- 접근 차단: Cloudflare에서 `INGEST_TOKEN`과 `READ_TOKEN`을 새 값으로 교체
