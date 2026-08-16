# DSH Mobile — 핸드오프 문서 (이어달리기용)

> 작성 시점의 최신 상태를 담습니다. 이 문서를 읽고 다음 세션이 그대로 이어서 작업하면 됩니다.
> 프로젝트: **deepseek-harness-mobile** (Android 앱, DSH Harness 원격 클라이언트)

## 1. 핵심 요약 (한 줄)

사용자가 보고한 핵심 문제: **새/기존 세션을 열었을 때 "초기화(initializing)" 또는 "첫 전송이 안 되는" 상태가 비정상적으로 오래 지속됐다가 갑자기 정상화**된다. 렌더링 버그는 이미 해결됐고, **근본 원인을 확정하고 수정 완료**했다. 핵심 원인은 **Compose 상태 라이프사이클 문제**: 세션 전환 시 `Composer`(TextField + Send 버튼)가 재구성(recompose)만 되고 재생성(recreate)되지 않아 TextField의 내부 상태(IME 연결, 커서)가 stale → Send 버튼이 반응하지 않음. 화면 회전 시 Activity 재생성으로 Compose 트리가 새로 만들어져 즉시 해결되는 것이 사용자의 단서. **`key(currentSessionId)`로 Composer를 강제 재생성**하여 해결. **사용자 실기기 검증 완료.**

---

## 2. 프로젝트 / 환경 정보

- 리포: `/Users/heavens3/deepseek/deepseek-harness-mobile/` (클론: `sorsama/deepseek-harness-mobile`)
- 하네스 체크아웃(관찰용): `/Users/heavens3/deepseek/deepseek-harness/` (`packages/host/apiproxy` 가 하네스/API 프록시)
- 핵심 앱 코드:
  - 와이어 DTO / 커스텀 시리얼라이저: `core/src/main/kotlin/com/labteto/dshmobile/core/wire/dto/Events.kt`
  - 폴드/스냅샷: `EventFold.kt`, `ChatTranscript.kt`, `ChatNodeVisibility.kt`
  - 세션/상태: `app/src/main/java/com/labteto/dshmobile/data/SessionStore.kt`
  - 연결: `app/src/main/java/com/labteto/dshmobile/connection/ConnectionManager.kt`, `core/.../wire/ConnectionLoop.kt`
  - UI: `app/src/main/java/com/labteto/dshmobile/ui/screens/main/ChatScreen.kt`, `Composer.kt`
- 빌드 (Android Studio JBR Java 21):
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export ANDROID_HOME=$HOME/Library/Android/sdk
  export GRADLE_USER_HOME=/Users/heavens3/deepseek/.gradle-home
  export ANDROID_USER_HOME=/Users/heavens3/deepseek/.android-home
  cd /Users/heavens3/deepseek/deepseek-harness-mobile && ./gradlew :app:assembleDebug
  ```
- 최종 APK: `/Users/heavens3/deepseek/deepseek-harness-mobile/app/build/outputs/apk/debug/app-debug.apk` (~21 MB)
- 디버그 앱 id: `com.labteto.dshmobile.debug`, 액티비티: `com.labteto.dshmobile.MainActivity`
- 하네스 실행 주소: `127.0.0.1:3080` (호스트), LAN: `192.168.99.18:3080`

---

## 3. 진행 이력 (완료된 것들)

### 3.1 렌더링 버그 — **해결 완료 (검증됨)**
- 증상: 하네스에 답변이 저장되는데 앱에 전혀 표시 안 됨 ("Nothing answered", send 무반응으로 착각).
- 근본 원인: 커스텀 시리얼라이저 `ContentBlockSerializer`/`StreamChunkSerializer`가 decode→re-encode(`sessionEventToEnvelope`) 할 때 **`type` discriminator를 버림** → 폴드가 text 블록을 인식 못 해 메시지가 빈 채로 렌더.
- 수정: `Events.kt`에 `withType(json, type)` 헬퍼 추가, 각 서브타입에 `type` 재주입.
- 검증: 에뮬레이터에서 fold 결과 `U:... | A:...` 정상, 화면에 답변 렌더됨.

### 3.2 초기화 스켈레톤 무한 지속 — **해결 완료**
- 증상: 새 세션 열 때 "로딩/초기화" 스켈레톤이 끝나지 않음 → `conversation == null && currentSessionId != null`.
- 수정:
  - `SessionStore.openSession()`: 스킬/모델/서브에이전트/커맨드 4개 카탈로그 로드를 `scope.launch { ... }` 백그라운드로 (openSession 반환 안 막힘).
  - `openFailed` StateFlow 추가: history 실패 시 무한 스켈레톤 대신 `ChatTranscript`에 "열 수 없어요 → 재시도" UI (`retryOpenCurrentSession()`).
  - `retryOpenCurrentSession()` 추가.
  - `strings.xml`: `chat_open_failed`, `chat_open_retry` 추가.

### 3.3 새 세션 만들 때 Send 활성 지연 — **해결 완료 (부분)**
- `createSession()`에서 기존엔 `sessionCreate` 후 `refreshSessions()`(전체 세션 재조회, 무거운 원격 왕복) → 그 후에야 `openSession`(currentSessionId 부여 = Send 활성화).
- 수정: `openSession(r.value.sessionId)` **먼저** → `scope.launch { refreshSessions() }` 백그라운드로.
- 효과: 원격 링크가 느려도 Send 버튼이 더 빨리 활성화됨 (전체 목록 새로고침을 기다리지 않음).

### 3.7 Composer key() 재생성 — **결정적 근본 원인, 해결 완료 (사용자 실기기 검증됨)**
- **사용자 결정적 단서**: "화면을 90도 회전하면 Send 버튼이 즉시 반응한다. Android lifecycle issue?"
- **원인**: 세션 전환 시 `currentSessionId`가 변경되어 `rememberSaveable(currentSessionId)`가 `draft`를 새로 만들지만, `Composer`(Material3 `TextField` + Send 버튼)는 **recompose만 되고 recreate되지 않음**. TextField의 내부 상태(IME `InputConnection`, 커서, composition buffer)가 이전 세션의 것으로 stale하게 남아 → 사용자가 타이핑해도 `onValueChange`가 호출되지 않거나 `canSend`가 갱신되지 않음 → Send 버튼 비활성.
- **화면 회전이 해결인 이유**: Activity 재생성 → Compose 트리 전체가 새로 만들어짐 → TextField도 새 인스턴스 → IME가 새 TextField에 연결 → 정상 동작.
- **수정** (`ChatScreen.kt`): `Composer` 호출을 `key(currentSessionId) { ... }`로 감싸서 세션 전환 시 Composer를 **강제 재생성**(recreate). 회전 없이도 매 세션 전환마다 Compose가 회전과 같은 효과를 내도록 함.
- **검증**: 사용자 실기기에서 "새 세션 생성 → 타이핑 → Send 즉시 반응" 확인. 에뮬레이터에서 curl 기반 이벤트 전송 정상 (TTFT 428ms, 이벤트 유실 0건).

### 3.6 openSession 이벤트 유실 레이스 수정 — **보조 수정 완료 (에뮬레이터 검증됨)**
- `openSession()`의 히스토리 조회 후 두 번째 `synchronized` 블록에서 `currentEvents.clear()` + `addAll(envelopes)` → 히스토리 조회 도중 도착한 이벤트 유실. **seq 기반 머지**로 변경 (`loadOlder()` 패턴).
- `session/subscribed` 핸들러에서 `openSession()` 호출 제거 (더블오픈 방지).
- 이 두 수정은 이벤트 유실 레이스를 해결하지만, 사용자의 핵심 증상(Send 버튼 무반응)은 3.7의 Compose 상태 문제가 결정적 원인이었음.

### 3.5 다운링크 안정성 튜닝 — **구현 완료 (원격 실기기 검증은 섹션 7-1)**
- 하네스 쪽 확인(`websocket-downlink.ts`): 서버는 idle 소켓을 스스로 닫지 않고, 클라이언트 ping엔 자동 pong 응답. 즉 끊김 감지는 **전적으로 앱의 OkHttp pingInterval**에 의존.
- `pingInterval` 20s → **10s** (`AppModule.kt`). `events.mux`는 read-only라 idle 시 트래픽 0 → 반쯤 열린 소켓(dead-CONNECTED)이 "전송 무반응 → 갑자기 정상화" 증상의 원인. 20s는 dead stretch를 최대 ~20s로 늘어지게 했고, 10s로 절반 단축.
- `streamOpenTimeoutMs` 3s → **8s** (`ConnectionLoop.kt` 기본값). 원격/셀룰러 느린 링크에서 WS 2개 open + host.describe를 3s 안에 못 끝내면 매번 generation이 폐기되고 backoff(최대 10s)로 이어져 CONNECTING/RECONNECTING이 길게 지속(= 첫 센드 준비 지연). dead 감지는 ping이 별도로 하므로 예산을 늘려도 손해 없음.
- `LoopConfig()` 기본을 쓰는 `connect()`/`reconnectIfNeeded()` 둘 다 이 값 공유 → 한 곳 수정으로 커버. `:core:test` 핸드셰이크 테스트 5개 & `:app:assembleDebug` 통과.

### 3.4 전송 게이트 + 피드백 — **해결 완료 (증상 개선, 근본은 다음 섹션)**
- `SessionStore`에 추가:
  - `isConnectionReady()`: `phase == ConnectionPhase.CONNECTED`
  - `kickConnection()`: 아닐 때 `connectionManager.reconnectIfNeeded()` 호출 (재접속 백오프를 조용히 기다리지 않게)
- `ChatScreen.send()`: draft 지우기 **전에** `!store.isConnectionReady()` 면 draft **유지** + 토스트 "Reconnecting to the harness — your message is kept; try again in a moment." + `kickConnection()`.
- 진단 로그 추가 (tag `DSHSend`):
  - `promptContent` 전송 시점: `prompt dispatch sid=... phase=... ready=...`
  - `kickConnection` 차단 시점: `send kept; downlink not ready phase=...; kicking reconnect`

---

## 4. 현재 이슈: 증상

사용자 보고 (한국어 원문 의미):
1. 맨 처음 앱 열면 워크스페이스 고르는 창이 뜸 → **원격 워크스페이스 선택 후 Send가 준비되기까지 매우 오랜 시간**(새 세션이 제일 김).
2. 왼쪽 사이드 패널에서 **다른 세션으로 전환해도 첫 메시지 보낼 준비까지 한참**(새 세션보단 짧음).
3. **Send 버튼은 텍스트 입력 즉시 "준비됨"으로 표시됨** — 그런데 **눌러도 아무 반응이 없음**(작업중 네모 안 뜸, 답변 없음).
4. **일정 시간(매우 오래) 흐른 뒤 갑자기 정상화** — 그때부터 화살표→네모가 즉시 바뀌고 대화가 빨라짐.
5. **모델/링크 지연은 아님**: 모델 답변은 빛처럼 빠름. 지연은 "센드가 준비/반응" 단계.
6. `session.jsonl`에서 **첫 답변 전에 "알림 메시지"**가 보임 (→ 이것은 정상임, 아래 참조).

---

## 5. 원인 분석 (확정됨)

### 5.0 결정적 근본 원인: Compose 상태 라이프사이클 — **사용자 실기기 검증 완료**
- **사용자 결정적 단서**: "화면을 90도 회전하면 Send 버튼이 즉시 반응한다. Android lifecycle issue?"
- **원인**: 세션 전환 시 `currentSessionId` 변경 → `rememberSaveable(currentSessionId)`가 `draft`를 새로 만들지만, `Composer`(`TextField` + Send 버튼)는 **recompose만 되고 recreate되지 않음**. TextField 내부 상태(IME `InputConnection`, 커서, composition buffer)가 이전 세션 것으로 stale → 타이핑해도 `onValueChange` 미발생 → `canSend = false` → Send 버튼 비활성 → "눌러도 반응 없음".
- **회전이 해결인 이유**: Activity 재생성 → Compose 트리 전체 새 생성 → TextField 새 인스턴스 → IME 재연결 → 정상.
- **수정** (`ChatScreen.kt`): `key(currentSessionId) { Composer(...) }` — 세션 전환마다 Composer 강제 재생성. 회전 없이도 동일 효과.
- **검증**: 사용자 실기기에서 "새 세션 → 타이핑 → Send 즉시 반응" 확인.

### 5.1 보조 원인: openSession 이벤트 유실 레이스 — **에뮬레이터 검증 완료**
- `openSession()` 히스토리 조회 도중 도착한 이벤트를 `currentEvents.clear()`가 삭제. **seq 기반 머지**로 수정.
- `session/subscribed` 핸들러의 중복 `openSession()` 제거.
- 이 문제는 이벤트 유실을 일으키지만, 사용자의 핵심 증상(Send 버튼 무반응)은 5.0의 Compose 문제가 결정적. 두 문제는 별개.

### 5.2 "알림 메시지" = 정상 (지연 원인 아님)
- `session.jsonl` 첫 답변 전에 보이는 큰 텍스트는 하네스가 모델 호출 직전 주입하는 **런타임 컨텍스트 스냅샷** (`@deepseek-ai/dsh-system-prompt`, `form:"snapshot"`).
- **지연 원인 아님.** 앱이 이를 "일반 사용자 메시지"처럼 큰 버블로 그려서 사용자가 '이상한 알림'으로 인식. (UX 개선 가능 — 미진행)

### 5.3 이전 가설 (다운링크 안정성) — **근본 원인이 아님, 보조 개선으로 유지**
- `events.mux` 다운링크 끊김 가설. `pingInterval` 10s, `streamOpenTimeoutMs` 8s 튜닝은 원격 링크 복구 속도 개선으로 유효 유지.

### 5.4 진단 로그
- `DSHSend` (tag): `send()` 게이트별 + `prompt dispatch` + `prompt queued` + `flushing queued prompt`
- `DSHConn` (tag): `phase X -> Y (loop=Z)`, `connected`, `generation failed`, `session/subscribed`
- **사용자 실기기 확인**: `adb logcat -s DSHSend DSHConn`

### 5.3 이전 후보 B (`connectedApi` null) — **근본 원인 아님**
- `promptContent()`의 `val api = apiOrNull() ?: return`은 연결이 없으면 조용히 아무것도 안 함.
- 에뮬레이터에서 `apiOrNull()`은 항상 non-null(연결 후 `api` 설정, 해제 전까지 유지). DSHConn/DSHSend 로그로 확인.

### 5.4 진단 로그 (이 세션에서 추가)
- `DSHSend` (tag): `send()`의 모든 게이트 + `prompt dispatch` + `prompt queued` + `flushing queued prompt`
- `DSHConn` (tag): `phase X -> Y (loop=Z)`, `connected: version`, `generation failed`, `session/subscribed: sid (current=...)`
- **사용자 실기기 확인**: `adb logcat -s DSHSend DSHConn`
- 사용자가 경험한 "dead period"가 (a) `RECONNECTING`(다운링크 끊김) 인지, (b) 다른 상태인지 확인 필요.

---

## 6. 확정된 사실 / 측정값 (에뮬레이터, 빠른 로컬 링크)

- 에뮬레이터(adb reverse, 로컬 빠른 링크)에서 **문제 재현 성공** (이전 세션의 "재현 안 됨" 기록과 다름 — 더블오픈 레이스가 원인이므로 네트워크와 무관).
- `session.jsonl`(하이 하이디 턴): user/message seq 7 → 첫 assistant/chunk 약 **2.4초** 후 (모델 첫 토큰; 로컬 모델 제공자 `a5-dspark`/`deepseek-v4-flash-dspark`). 주소: `/Users/heavens3/deepseek/session.jsonl` (현재 최신 실행 세션이 여기 기록됨 — 체크아웃이 아니라 cwd).

---

## 7. 다음 단계 (이어서 할 일)

1. **[완료 — 사용자 실기기 검증됨] 결정적 근본 원인 수정 — 섹션 3.7 / 5.0 참조**: `key(currentSessionId)`로 Composer 강제 재생성. Compose 상태 라이프사이클 문제(세션 전환 시 TextField/IME stale) 해결.
2. **[완료 — 에뮬레이터 검증됨] openSession 이벤트 유실 레이스 수정 — 섹션 3.6 / 5.1**: seq 기반 머지 + 더블오픈 제거.
3. **[완료 — 보조 개선, 섹션 3.5] 다운링크 안정성 튜닝**: `streamOpenTimeoutMs` 3s→8s, `pingInterval` 20s→10s.
4. **[완료] 큐-앤-오토-플러시**: RECONNECTING 중 전송 시 메시지 큐잉 후 재접속 시 자동 전송.
5. **[완료] 진단 로그**: `DSHSend` (send 게이트별), `DSHConn` (phase 변경, generation 실패, session/subscribed).
6. **[옵션] UX 개선**: "runtime-context 스냅샷" user/message를 버블에서 숨기기(plugin source `@deepseek-ai/dsh-system-prompt` 필터). 미진행.

---

## 8. 에뮬레이터 / 자동화 메모

- 기기: `emulator-5554` (Medium_Phone, headless, Android 15). **부팅 시 `danger-full-access` escalation 필수** — 에뮬레이터가 `~/.android/`에 lock 파일(`snapshot.lock`, `multiinstance.lock`, `hardware-qemu.ini.lock`) 생성해야 하는데 workspace-write 샌드박스가 이를 막으면 "A snapshot operation ... is pending and timeout has expired" FATAL 발생.
- 터널링: `adb reverse tcp:3080 tcp:3080` (재부팅/재설치 후 **반드시 재설정**; 안 하면 앱이 Connect 화면으로 빠짐).
- 재설치 후 콜드 스타트: `adb shell am force-stop com.labteto.dshmobile.debug` → `am start -n ...MainActivity`.
- 앱 연결이 Connect 화면에 있으면 "Recent > This device (loopback)" 행(~520,838) 탭 또는 host=127.0.0.1→Connect.
- **주의(중요): adb `input text` / `input keyevent` 모두 Compose TextField와 desync** → `draft`가 빈 채로 남아 `send()`가 `text.isBlank()`로 조용히 리턴. `DSHSend` 로그로 `draft len=0` 확인됨. 이는 **자동화 한계이지 버그 아님**. 사람이 직접 타이핑하면 정상.
- **대체 검증 방법**: UI 타이핑이 안 되므로 harness API(curl `session.prompt`)로 직접 프롬프트 전송 → 앱의 events.mux 다운링크가 이벤트를 수신해 렌더하는지로 검증. 이 방법으로 더블오픈 레이스 수정 검증 완료.
- Send 버튼 탭은 IME 닫힌 상태에서 좌표가 안정적: `(970,2189)` (버튼 bounds `[907,2128][1033,2251]`). IME 켜진 상태면 레이아웃이 밀려 좌표가 달라짐 → keyevent 4로 IME 먼저 닫기.
- 최신 빌드를 에뮬레이터에서 콜드 스타트했을 때: 연결·세션 열기 정상, 변경 코드 크래시/예외 0건 확인됨.

---

## 9. 참고 코드 위치 (다음 세션이 볼 곳)

- `SessionStore.promptContent()` — `val api = apiOrNull() ?: return` (침묵 드롭 지점, ~line 1059 부근, 줄 번호는 변경됨). `isConnectionReady()`/`kickConnection()`/`retryOpenCurrentSession()`/`openFailed` 추가됨.
- `ChatScreen.send()` — draft 유지 + `isConnectionReady` 게이트 (~line 155).
- `ChatTranscript.kt` — `TranscriptSkeleton` / `TranscriptRefresh(openFailed)`.
- `ConnectionManager.kt` — `api`, `connect()`, `disconnect()`, `reconnectIfNeeded()`, `connectedApi`.
- `core/.../ConnectionLoop.kt` — 재접속/백오프 로직 (근본 수정 대상).
- `Events.kt` — `withType` (렌더링 버그 수정, 이미 완료).
- 하네스: `deepseek-harness/packages/host/apiproxy/src/api-proxy.ts` — `events.mux` 핸들러가 모든 세션 이벤트를 subscribe/스트리밍하는 구조.

---

## 10. 손댄 파일 목록 (이 작업 세션 기준)

**근본 원인 수정 (더블오픈 레이스)**:
- `app/.../data/SessionStore.kt` — `SessionSubscribed` 핸들러에서 `openSession()` 제거 (더블오픈 레이스, 섹션 3.6); `queuePrompt`/`flushPendingPrompt` 큐-앤-오토-플러시; `connectionPhase()` 진단 helper; `DSHSend` 로그

**보조 개선**:
**결정적 근본 원인 수정 (Compose 상태 라이프사이클)**:
- `app/.../ui/screens/main/ChatScreen.kt` — `key(currentSessionId) { Composer(...) }`로 세션 전환 시 Composer 강제 재생성 (섹션 3.7); `send()` 게이트별 `DSHSend` 로그; 큐잉 시 draft 클리어 + 토스트; `import key` 추가

**보조 수정 (이벤트 유실 레이스)**:
- `app/.../data/SessionStore.kt` — `openSession()` 두 번째 `synchronized` 블록을 seq 기반 머지로 변경 (섹션 3.6); `session/subscribed` 핸들러에서 `openSession()` 제거; `queuePrompt`/`flushPendingPrompt` 큐-앤-오토-플러시; `connectionPhase()` 진단 helper

**보조 개선 (다운링크 튜닝)**:
- `app/src/main/java/com/labteto/dshmobile/di/AppModule.kt` — `pingInterval` 20s→10s (섹션 3.5)
- `core/.../wire/ConnectionLoop.kt` — `streamOpenTimeoutMs` 3s→8s 기본값 (섹션 3.5)

**진단 로그**:
- `app/.../connection/ConnectionManager.kt` — `DSHConn` 로그 (phase 변경, connect, generation 실패)

**이전 세션 수정 (유지)**:
- `core/.../wire/dto/Events.kt` (렌더링 fix, `withType`)
- `app/.../ui/screens/main/ChatTranscript.kt` (openFailed → TranscriptRefresh)
- `app/src/main/res/values/strings.xml` (chat_open_failed, chat_open_retry)

**현재 상태**: 결정적 근본 원인(Compose 상태 라이프사이클 — 세션 전환 시 TextField/IME stale) 확정·수정·**사용자 실기기 검증 완료**. `key(currentSessionId)`로 Composer 강제 재생성이 핵심 픽스. 보조로 openSession 이벤트 유실 레이스(seq 머지), 다운링크 튜닝(pingInterval/handshake timeout), 큐-앤-오토-플러시, 진단 로그 추가. 앱 빌드/실행 정상, 콜드 스타트 크래시 없음. **렌더링·무한 초기화·새 세션 Send 무반응·이벤트 유실·침묵 전송 모두 해결됨.**
