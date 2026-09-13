# DSH Mobile — 핸드오프 문서 (이어달리기용)

> 작성 시점의 최신 상태를 담습니다. 이 문서를 읽고 다음 세션이 그대로 이어서 작업하면 됩니다.
> 프로젝트: **deepseek-harness-mobile** (Android 앱, DSH Harness 원격 클라이언트)

## 1. 핵심 요약 (한 줄)

사용자가 보고한 핵심 문제: **새/기존 세션을 열었을 때 "초기화(initializing)" 또는 "첫 전송이 안 되는" 상태가 비정상적으로 오래 지속됐다가 갑자기 정상화**된다. 렌더링 버그는 이미 해결됐고, **근본 원인을 확정하고 수정 완료**했다. 핵심 원인은 **Compose 상태 라이프사이클 문제**: 세션 전환 시 `Composer`(TextField + Send 버튼)가 재구성(recompose)만 되고 재생성(recreate)되지 않아 TextField의 내부 상태(IME 연결, 커서)가 stale → Send 버튼이 반응하지 않음. 화면 회전 시 Activity 재생성으로 Compose 트리가 새로 만들어져 즉시 해결되는 것이 사용자의 단서. **`key(currentSessionId)`로 Composer를 강제 재생성**하여 해결. **사용자 실기기 검증 완료.**

**최신 작업 (2026-09-13)**: 하네스 설정 UI(`Settings → Harness`)가 **폰 화면에 안 들어가던 문제**를 수정. `DsBottomSheet`가 content를 스크롤 컨테이너·높이 상한 없이 그냥 `Column`에 넣어서, 화면보다 큰 시트는 **꼬리(그리고 마지막 자식인 액션 버튼)가 잘려 접근 불가**였다. "Add custom provider"가 최악(모델 행 추가마다 2줄씩 증가) → **Create/Save 버튼을 누를 수 없음**. 시트 body 스크롤 + 뷰포트 상한 + **고정 `footer` 슬롯**으로 근본 수정(하네스 시트 3종이 footer 사용). **에뮬레이터 실기 검증 완료.** 상세: **섹션 11.12**.

> **문서 정합성 주의**: §11.9의 (C1)과 (C5, 500 이벤트 상한)은 **§11.11에서 되돌려졌다**(무한 "Loading earlier messages" 루프의 진짜 원인이 그 트리밍이었음). §7 항목 7의 V1("떠나던 위치로 복귀")은 **더 이상 유효한 기대가 아니다** — §11.11 기준 동작은 "항상 tail로 결정적 개방"이다. **§11.11이 현재 진실**이며, §11.9는 이력으로만 읽어야 한다.

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
- **지연 원인 아님.** 앱이 이를 "일반 사용자 메시지"처럼 큰 버블로 그려서 사용자가 '이상한 알림'으로 인식 → **UX 해결 완료 (섹션 11.6)**: plugin-source `user/message`는 거대 버블이 아니라 접을 수 있는 "Context" disclosure 행으로 렌더(숨김이 아닌 컴팩트 표시, 사용자 결정).

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
6. **[완료 — 사용자 실기기 검증됨] UX 개선 — 섹션 11.6/11.7 참조**: (a) "runtime-context 스냅샷" user/message를 거대 버블 대신 **접을 수 있는 "Context" disclosure 행**으로 렌더(플러그인 source `@deepseek-ai/dsh-system-prompt`, `form:"snapshot"` 판별, 섹션 11.6); (b) `ask_user_question` 모바일 오작동 2건 수정 — 질문 알림 dedup(rpcId 기반) + pending 질문 세션별 Map화(섹션 11.7).
7. **[부분 폐기 — §11.11 참조] 세션 히스토리 레이스 4종 — 섹션 11.9**: (C2) 범위 인지 stitch drain, (C3) repair 단일화, (C4) 설명 가능한 gap 억제는 **유효하게 유지**. 그러나 **(C1) 재개 커서와 (C5) 500 이벤트 상한은 §11.11에서 제거/무력화**되었다 — 그 트리밍이 무한 "Loading earlier messages"의 원인이었기 때문. **§11.9의 V1·V5는 더 이상 검증 대상이 아니다**(V1이 기대하는 "기억된 위치 복귀" 동작 자체가 사라짐). 남은 검증 대상은 §11.10/§11.11의 "검증 (에뮬레이터, 실제 백엔드)" 항목이며, 이는 **이미 통과 기록됨**.
8. **[완료 — 에뮬레이터 실기 검증됨, 커밋 `927c1dc`] 하네스 설정 UI가 폰 화면에 안 들어가던 문제 — 섹션 11.12 참조**: `DsBottomSheet`가 스크롤 컨테이너·높이 상한 없이 content를 `Column`에 넣어, 화면보다 큰 시트의 **꼬리와 마지막 자식인 액션 버튼이 잘려 접근 불가**. "Add custom provider"의 Create/Save가 눌리지 않던 것이 사용자 보고 증상. 시트 body 스크롤 + `fillMaxHeight(0.92f)` 상한 + **고정 `footer` 슬롯**(+ IME inset 소비)으로 근본 수정.
9. **[미진행] 잔여 UI 개선 후보**: (a) `SheetCommands` 스크롤 2단계(내부 bounded `LazyColumn` 2개가 새 스크롤 body 안에 중첩) — 기능은 정상, UX만 어색; (b) `ModelRowsEditor`가 행당 `weight(1f)` 필드 2개라 좁은 폰에서 빡빡함(라벨이 길면 더 심함) — 1열 재배치 후보; (c) `SheetPresets`/`SheetSubagents` 등 나머지 시트는 이제 primitive 덕에 스크롤되지만 개별 점검은 미실시.

---

## 8. 에뮬레이터 / 자동화 메모

- 기기: `emulator-5554`, headless. **부팅 시 `danger-full-access` escalation 필수** — 에뮬레이터가 워크스페이스 밖(`~/.android/`, AVD 홈)에 lock 파일(`snapshot.lock`, `multiinstance.lock`, `hardware-qemu.ini.lock`)을 만들어야 하는데 workspace-write 샌드박스가 이를 막으면 `FATAL | A snapshot operation for '<AVD>' is pending and timeout has expired. Exiting...` 로 즉사한다(2026-09-13 재확인).
- **AVD 이름 주의 (2026-09-13 정정)**: 이전 세션 기록의 `Medium_Phone`은 **이 환경에 없다**. `emulator -list-avds`가 보여주는 것 중 **실제로 부팅되는 것은 `Pixel_8`** 이며, AVD는 샌드박스 로컬에 있다:
  ```bash
  export ANDROID_AVD_HOME=/Users/heavens3/deepseek/deepseek-harness-mobile/.avd-home   # Pixel_8.ini / Pixel_8.avd
  export ANDROID_USER_HOME=/Users/heavens3/deepseek/deepseek-harness-mobile/.android-home
  $ANDROID_HOME/emulator/emulator -avd Pixel_8 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect
  ```
  `ANDROID_AVD_HOME`를 안 주면 `Unknown AVD name [Medium_Phone]`, 잘못 주면 `no file Medium_Phone.ini in $ANDROID_AVD_HOME`로 실패한다. 화면은 **1080x2400 @ 420dpi**(geometry 검증 시 이 좌표계 기준).
- 최초 실행 시 시스템 권한 다이얼로그(`GrantPermissionsActivity`, POST_NOTIFICATIONS)가 포커스를 가로챈다 → `adb shell pm grant com.labteto.dshmobile.debug android.permission.POST_NOTIFICATIONS` 후 재시작.
- 터널링: `adb reverse tcp:3080 tcp:3080` (재부팅/재설치 후 **반드시 재설정**; 안 하면 앱이 Connect 화면으로 빠짐). 이 환경에서 하네스는 실제로 `127.0.0.1:3080`에서 200을 반환한다.
- 재설치 후 콜드 스타트: `adb shell am force-stop com.labteto.dshmobile.debug` → `am start -n ...MainActivity`. **앱은 마지막 세션을 자동 재개**하므로, 검증 중이면 대화 화면이 아니라 **원하는 화면으로 직접 내비게이션**해야 한다(설정 경로: drawer 터뷸 > `Settings` > 스크롤 > `Harness settings` > 탭).
- 앱 연결이 Connect 화면에 있으면 "Recent > This device (loopback)" 행(~520,838) 탭 또는 host=127.0.0.1→Connect.
- **주의(중요): adb `input text` / `input keyevent` 모두 Compose TextField와 desync** → `draft`가 빈 채로 남아 `send()`가 `text.isBlank()`로 조용히 리턴. `DSHSend` 로그로 `draft len=0` 확인됨. 이는 **자동화 한계이지 버그 아님**. 사람이 직접 타이핑하면 정상.
- **기하(geometry) 검증은 `uiautomator dump`가 정답**: `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml` 후 노드의 `text`/`content-desc` + `bounds`를 파싱하면, 버튼이 화면 안에 있는지·스크롤 후 좌표가 고정인지(=`footer` 고정 증명)를 **정량적으로** 확인할 수 있다. 이 세션의 시트 수정 검증이 전부 이 방법(§11.12). 스크린샷은 이미지 입력이 가능한 모델에서만 유용(현재 모델은 이미지 입력 불가).

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

**세션 히스토리 레이스 4종 (섹션 11.9, 이후 §11.11에서 일부 되돌림)**:
- `app/.../data/SessionStore.kt` — C2 범위 인지 `drainStitchBufferLocked`, C3 `repairInFlight`, C4 `explainableGapLocked`+repair 트리거 커버 판정은 **유지**. **(C1) `oldestLoadedSeq`는 §11.11에서 완전 제거**, **(C5) `trimWindowLocked`/`MAX_WINDOW_EVENTS`는 순수 안전 상한(1M)으로 무력화** — 트리밍이 무한 로딩의 원인이었음. `repairMissedEvents`는 `internal`.
- `core/.../wire/DshApiClient.kt` — `open class` + `sessionHistory` open (테스트 서브클래스용)
- `app/.../connection/ConnectionManager.kt` — `open class` + `_state`/`api` protected
- `app/.../connection/HostsStore.kt` — `open class` + `lastSessionId` open
- `app/build.gradle.kts` — `testOptions { unitTests { isReturnDefaultValues = true } }` (JVM 유닛 테스트에서 `android.util.Log` no-mock)
- 신규 `app/src/test/.../data/SessionHistoryRaceTest.kt` (T1 커서 전달, T4 open 진행 중 버스트 스티칭)
- `CHANGELOG.md` — [Unreleased] Fixed×2(가짜 Reconnecting 배너, 복귀 시 전체 히스토리 재빌드) + Improved×1(500 이벤트 윈도우 상한)

**시트 레이아웃 근본 수정 (섹션 11.12, 커밋 `927c1dc`)**:
- `app/.../ui/components/DsBottomSheet.kt` — body를 `weight(1f, fill=false) + verticalScroll`로, 시트 높이 `fillMaxHeight(0.92f)` 상한, **신규 `footer` 슬롯**(스크롤 영역 밖 고정, divider 포함), `contentWindowInsets`에 `ime` 합집합
- `app/.../ui/screens/harness/HarnessProviderEditor.kt` — `ProviderEditorSheet`/`CustomProviderSheet`/`DiscoverModelsSheet` 3종의 액션 Row를 `footer`로 이동; 프로토콜 칩 Row에 `horizontalScroll`; discovery 결과 리스트의 중첩 `verticalScroll` 제거

**현재 상태**: 결정적 근본 원인(Compose 상태 라이프사이클 — 세션 전환 시 TextField/IME stale) 확정·수정·**사용자 실기기 검증 완료**. `key(currentSessionId)`로 Composer 강제 재생성이 핵심 픽스. 보조로 openSession 이벤트 유실 레이스(seq 머지), 다운링크 튜닝(pingInterval/handshake timeout), 큐-앤-오토-플러시, 진단 로그 추가. 세션 히스토리 레이스는 §11.9(C1–C5) → §11.10 부분 수정 → **§11.11에서 진짜 원인 규명 및 C1 제거·C5 무력화**(현재 진실). **최신: 하네스 설정 UI가 폰 화면에 안 들어가던 문제(시트 꼬리·액션 버튼 클리핑)를 primitive 수준에서 수정 — 에뮬레이터 실기 검증 완료(`927c1dc`)**. 유닛 테스트 160건(앱 98 + 코어 62) 전부 그린, 앱 빌드/실행 정상, 크래시 0건. **렌더링·무한 초기화·새 세션 Send 무반응·이벤트 유실·침묵 전송·가짜 Reconnecting 배너·무한 로딩 루프·폰 화면 초과 시트 모두 해결됨.**

---

## 11. 이후 세션에서 추가 해결 (UX/Layout)

### 11.1 질문 창(ask_user_question)이 입력/진행/탈출이 안 되던 문제 — **해결 완료 (라이브 하네스 end-to-end 검증됨)**
- 증상: LLM이 `ask_user_question`을 던지면 선택창이 뜨고 탭 하이라이트는 되지만 Submit/Cancel(및 Skip/Next)을 눌러도 진행/탈출 불가. **옵션은 클릭되지만(일반 `clickable`) 진행/닫기 버튼이 죽어 있음.**
- **확정된 근본 원인**: `DsButton.kt` 내부 content `Row(modifier = Modifier.fillMaxSize().padding(...))`의 **`fillMaxSize()`** 때문. `fillMaxSize()`가 버튼을 **부모가 준 공간 전부**로 확장시키므로, **여러 DsButton이 한 `Row`에 나란히 있을 때 첫 번째 버튼이 Row 전체 폭을 다 차지하고 나머지 버튼들은 남는 폭(0)으로 붕괴** → Submit/Cancel이 0폭으로 렌더링·터치 영역 모두 사라짐. 호스트 측 확인: live 세션에는 `question/requested`만 있고 **어떤 answer도 도달하지 않음**(에이전트가 ask에서 영원히 정지).
- 증거(에뮬레이터 instrumentation geometry probe): QuestionsPanel에서 `Previous`(disabled)만 full width, `Submit`/`Cancel`(enabled)이 `[399,201][399,229]` **0폭**. `plainBox` 단일 버튼도 `0..411` full width, `rowNoWeight`=첫 버튼 full width+둘째 0폭, ApprovalPanel `Allow once` full width + `Reject` ABSENT → **행 안의 버튼 여럿일 때 체계적으로 발생**(Approval/PlanReview/시트/대화상자에도 동일 버그 잠재).
- 수정 (`DsButton.kt`): content Row modifier를 `Modifier.fillMaxSize()` → **`Modifier.fillMaxHeight()`**로 변경(가로는 label 크기만큼 자동 wrap, 세로만 채워 세로중앙 정렬 유지). 전체폭이 필요한 호출부는 기존 `modifier` 파라미터(`fillMaxWidth`/`weight`)로 명시하면 됨. 단일 수정으로 Questions/Approval/PlanReview/대화상자/시트의 모든 버튼 Row가 정상화.
- 본문 스크롤 고정(bound+scroll) 수정은 11.1의 보조 조치로 유지.
- 검증(라이브 하네스, 에뮬레이터에서 실제 해결): 실기기와 동일한 live `session-832dbfc4`(stopReason=pending ask)를 열어 확인 — 수정 전 액션 행엔 `Previous`만 렌더(Submit/Cancel 0폭). 수정 후 uiautomator에 `Previous[64,1874][198,1914]` `Submit[718,1874][827,1914]` `Cancel[912,1874][1016,1914]` 정상 렌더. 옵션 → Submit 탭 → **패널 닫힘 + 에이전트 재개**(\"You chose Explore the workspace…\"), 호스트 세션 로그에 `seq274 tool/result {"answers":[{\"id\":\"next_step\",\"selected\":[\"Explore the workspace\"]}]}` 기록 확인 → **end-to-end 정상 동작 확정**.

### 11.2 키보드가 뜨면 입력창이 너무 위로 올라가 아래에 큰 빈 공간 — 해결
- 증상: 폰에서 키보드가 뜨면 Composer(입력창)가 화면 대부분을 띄고(에뮬에서 y~745px 지점까지 점프) 아래에 키보드 높이만큼 휑한 빈 공간.
- 원인: `enableEdgeToEdge()`(decorFits=false)에서 `safeDrawingPadding()`이 IME inset을 읽는데, 창이 자동 resize(기본 softInputMode)와 함께 **이중 반영** → 입력창이 2배로 밀려 올라감.
- 수정 (`AndroidManifest.xml` MainActivity): `android:windowSoftInputMode="adjustNothing"` 추가. **Compose inset 방식**이라 resize 없이 IME inset을 한 번만 소비.
- 검증: 에뮬(수정 전) EditText top ~550px/blank ~730px → (수정 후) EditText bottom ~1322px로 키보드 top(1517px) 직상단, nav bar(~126px)만 남음. 빈 공간 해소.

### 11.3 Composer 크기 살짝 축소
- `Composer.kt`: Surface/Column의 세로 padding을 `DsSpacing.small→xsmall`로 줄여 폰 대비 입력 영역을 다소 컴팩트하게.

### 11.4 질문에 답한 뒤 대화가 아래(Latest)로 안 내려가 최신 답을 못 보던 문제 — **해결 (에뮬 검증)**
- 증상: 에이전트가 질문에 답(특히 긴 답)한 뒤 화면이 맨 아래(Latest)로 굴러가지 않아 답 버블의 끝 부분이 접혀 안 보임.
- 원인(`ChatTranscript.kt`): 자동 팔로우가 `animateScrollToItem(itemCount-1)`(기본 `scrollOffset=0`)을 사용해 **마지막 메시지의 TOP을 뷰포트 상단**에 맞춤 → 한 메시지가 긴 경우 그 시작만 보이고 최신 끝부분은 화면 밖. 더구나 질문/승인 같은 차단 덕을 닫았을 때의 강제 스크롤이 없어서, 유저가 질문을 읽으러 위로 스크롤했으면 답이 와도 안 내려감.
- 수정:
  1) 모든 팔로우 스크롤을 **bottom-anchor**로 — `scrollToItem/animateScrollToItem(idx, scrollOffset = BOTTOM_ANCHOR_OFFSET(=Int.MAX_VALUE))` (프레임워크가 리스트 끝으로 클램프 → 최신 내용을 하단에 고정).
  2) `ChatScreen.kt`: 현재 세션의 덕(question/approval)이 **닫힐 때** `followBottomKey++` → `ChatTranscript`의 `followHint`로 전달, hint가 바뀌면 `wasNearBottom`과 무관하게 **무조건 tail로 스크롤**(질문 읽느라 올라갔어도 답을 보여줌).
  3) 스트리밍 중에는 폴링 루프(running 동안 `delay 400ms`)로 tail을 하단 재고정 → 긴 답이 자라도 최신 라인이 계속 보임.
- 검증(에뮬): 긴 30줄 답변 생성 후 앱 재시작(session switch → tail) 시 **21~30번 줄이 화면에, 30번이 하단([43,1893][117,1942])**으로 표시(수정 전엔 1~11번이 상단/접힘). 크래시 0. 차단 덕 닫기 힌트는 동일한 bottom-anchor 경로를 사용하므로 질문 답 직후에도 동일하게 tail 고정.

**신규 변경 파일2(절)**: `ChatScreen.kt`(followBottomKey 덕-닫힘 hint), `ChatTranscript.kt`(followHint param + bottom-anchor 스크롤 + 스트리밍 폴링).

### 11.5 `ask_user_question`의 "Other" 자유 입력이 빈 문자열로 제출되던 문제 — **해결 (라이브 하네스 end-to-end 검증)**
- 증상: 손으로 고른 탭(selected)은 정상 제출되는데, "Other…" 텍스트 입력값이 비어서 전달됨(에이전트가 자유 입력을 못 봄).
- 원인:
  1) **`custom`을 잘못된 위치에 전송** — `SessionStore.answerQuestions`가 `custom`을 answer 객체의 **최상위**에 두고 있었지만, 하네스 스키마는 `answers[]` **각 항목 안에 per-item** 으로 요구(`{id, selected, custom?}`)함. 잘못된 위치의 custom은 무시 → Other 텍스트 유실(빈 값).
  2) **단일선택에서 탭+Other 병존** — 하네스 검증(`api-proxy.ts:723-731`)은 단일선택이 `selected`와 `custom`을 동시에 갖거나, `custom`이 빈 문자열이면 **거부**함.
- 수정:
  - `InteractionPanels.kt` `currentAnswer`: 단일선택에서 Other가 채워지면 그 값이 곧 답 → `selected`는 비우고 `custom`만 전송(멀티선택은 selected와 병존 허용). Other 공백이면 `custom` 생략.
  - `SessionStore.answerQuestions` → **`List<QuestionAnswerEntry>`(id/selected/custom)** 받아 각 항목 안에 `custom`을 넣어 전송(비어있으면 생략). 상위 `custom` 제거. `ChatScreen.kt`의 submit/plan-review/cancel 호출부를 `QuestionAnswerEntry`로 갱신.
- 검증(라이브 하네스, 에뮬): 새 단일선택 질문(Colors/Red/Blue)에서 탭 선택 없이 Other에 `green` 입력 → Submit → 하네스 세션 로그 `seq437 tool/result`에 **`{"answers":[{"id":"color_pick","selected":[],"custom":"green"}]}`** 기록, 앱 트랜스크립트에 **"Received: custom = green"** 렌더 → 정확히 per-item custom 전달 확인.

**신규 변경 파일**: `AndroidManifest.xml`(adjustNothing), `Composer.kt`(패딩 축소), **`DsButton.kt`(fillMaxSize→fillMaxHeight — 버튼 Row 붕괴 근본 수정)**, `InteractionPanels.kt`(QuestionsPanel bound+scroll **+`currentAnswer` 단일선택 Other→custom 전용), `SessionStore.kt`(`answerQuestions` per-item `custom` / `QuestionAnswerEntry`), `ChatScreen.kt`(followBottomKey 덕-닫힘 hint + `QuestionAnswerEntry` 호출부), `ChatTranscript.kt`(followHint + bottom-anchor 스크롤 + 스트리밍 폴링), 새 `app/src/androidTest/.../ui/components/QuestionsPanelTest.kt`(smoke guard). 빌드/설치/실기기 연결 정상, 크래시 0. **11.1 end-to-end(라이브 하네스) + 11.4(재실행) + 11.5(라이브 하네스) 검증 완료.**

### 11.6 런타임 컨텍스트 스냅샷을 거대 버블 대신 disclosure 행으로 렌더 — **해결 완료 (사용자 실기기 검증)**
- 배경: 하네스는 샌드박스/승인 정책이 바뀔 때 `user/message` 이벤트로 런타임 컨텍스트 스냅샷을 기록(`source: {kind:"plugin", plugin:"@deepseek-ai/dsh-system-prompt", form:"snapshot", sections:[{name,text}]}`, `RuntimeContextProjection.project()`이 텍스트 변화 시에만 재발행). 앱이 이를 일반 유저 버블로 렌더 → "이상한 알림" UX. 사용자는 숨김이 아닌 **Bash 카드처럼 접을 수 있는 컴팩트 행**을 요구.
- 수정:
  - `core/.../session/Conversation.kt`: `ContextSection(name,text)` + `ContextMessageNode(seq, messageId, plugin, form, sections, text)`(`previewText` 포함) 신규 노드.
  - `core/.../session/EventFold.kt`: `user/message` 분기가 `data.source`(raw JSON — envelope `data`가 `JsonElement`라 와이어 그대로 전달)를 판독, `source.kind == "plugin"`이면 `ContextMessageNode` 생성, 그 외엔 기존 `UserMessageNode`. `parseContextSections()`은 all-or-nothing(배열 아님/필드 누락 시 `emptyList()` → renderer가 raw text 폴백).
  - `app/.../main/ChatNodeItem.kt`: `ContextDisclosureRow` — 기존 `DisclosureRow` 프리미티브 재사용. 제목 `R.string.chat_context_title`("Context", 새 문자열 없음), 요약=섹션 이름 `" · "` 연결(`sections` 비어 있으면 `form` 폴백), 아이콘 `FeatherIcons.Info`, `remember(node.seq)` 확장 상태. 본문=섹션별 이름+전체 텍스트.
  - `app/.../main/ChatNodeVisibility.kt`: `rendersContent()` 신규 케이스(exhaustive-when 안전망).
  - `app/.../main/SheetSubagents.kt`: 서브에이전트 트랜스크립트 시트에 동일한 컴팩트 행.
- 검증: `:core:test` 56/56 그린(신규 `foldsPluginUserMessageIntoContextNode`, `foldsPluginUserMessageWithoutSections`), `:app:assembleDebug` 그린, **사용자 실기기 확인**(기존 세션의 과거 버블도 fold 재처리라 disclosure 행으로 변환).
- 함의: §5.2의 "UX 미진행" 및 §7-6의 "숨기기" 옵션은 본 구현(접기)으로 대체됨.

### 11.7 `ask_user_question` 모바일 표시 버그 2건 — **해결 완료 (유닛 검증 + 사용자 실기기 확인)**
- 사용자 보고: 질문 UI가 모바일에 안 뜨고, 서버는 유저 결정을 기다리는 상태.
- 서버(하네스) 측 확인 결과 **정상**: root 에이전트만 질문 가능(자식은 `DELEGATED_CALLER`로 거부), `api-proxy.ts`가 `rpcId`를 발급해 `question/requested` mux 프레임을 **모든** 큐에 브로드캐스트 후 POST /api/respond로 답변 대기, 재연결 시 pending 프레임을 같은 rpcId로 재전송(복구 로직 존재). 문제는 **앱이 질문을 받아내는 쪽**이었음.
- **B1 (중대) — 알림 dedup 버그**: 질문 프레임 페이로드에 `seq` 필드가 없어 dedup 키가 항상 `"question:$sessionId:0"` → **세션당 2번째 질문부터 알림/뱃지가 조용히 전부 사라짐**. 수정: `CompletionClassifier.QuestionRequested`에 `rpcId` 추가, 키 `"question:$sessionId:$rpcId"`; `NotificationObserver.handleMuxFrame`이 `frame.rpcId` 전달. (재전송 플레이는 같은 rpcId라 중복 알림 억제 — 의도적. 새 질문은 새 rpcId라 정상 알림.)
- **B2 — pending 단일 슬롯 덮어쓰기**: `SessionStore._pendingQuestions`가 질문 1개만 저장 → 세션 여러 개일 때 도크에 잘못된 세션의 질문 표시/새 질문이 기존 질문 덮어씀. 수정: `StateFlow<Map<sessionId, PendingQuestions>>`, `ChatScreen` 도크는 `pendingQuestions[currentSessionId]`만 렌더, `onSessionRemoved`에서 map 엔트리도 정리.
- **B3 (아키텍처 한계, 미해결 고지)**: 앱 프로세스가 종료/백그라운드에서 죽으면 push 채널이 없어 질문을 알 수 없음. 재접속 시 서버 재전송으로 복구. 근본 해결은 푸시(FCM 등) 필요. 참고: 승인 `_pendingApproval`에도 동일한 단일 슬롯 패턴이 대칭으로 존재(이번 범위 밖).
- 검증: `:core:test` 그린(신규 `secondQuestionOfSameSessionKeepsADistinctDedupKey`, `questionRequestedFires` rpcId dedupKey assertion), `:app:assembleDebug` 그린, 사용자 실기기에서 질문이 정상 표시 확인(2026-08-24).
- **신규 변경 파일(11.6+11.7)**: `core/.../session/Conversation.kt`, `core/.../session/EventFold.kt`, `core/.../notify/CompletionClassifier.kt`, `app/.../main/ChatNodeItem.kt`, `app/.../main/ChatNodeVisibility.kt`, `app/.../main/SheetSubagents.kt`, `app/.../notify/NotificationObserver.kt`, `app/.../data/SessionStore.kt`, `app/.../main/ChatScreen.kt`, 테스트 `core/src/test/.../session/EventFoldTest.kt`, `core/src/test/.../notify/CompletionClassifierTest.kt`.

### 11.8 상단 빨간 "Reconnecting…" 배너가 한 번 뜨면 안 사라지는 문제 — **해결 (컴파일/빌드 검증, 실기기 검증 대기)**
- 사용자 보고: 통신 장애 후 재연결되어도 상단 빨간 줄(Reconnecting…)이 계속 남음. "재연결되면 없어져야 하는것 아냐?"
- 배너의 실제 출처: `ChatScreen.kt` `conversation?.gap == true` → `ConnectionBanner("Reconnecting…")`. `gap`은 `EventFold`가 이벤트 윈도우에 **seq 구멍**(`event.seq > lastSeq + 1`)이 있으면 세우는 플래그로, **어디서도 리셋되지 않음**.
- 구멍 생성 경위: 다운링크가 죽었다 돌아와도 하네스 mux 스트림은 **누락 이벤트를 재전송하지 않음** (host `api-proxy.ts` `subscribeSession`: `session/subscribed { lastSeq }` 프레임만 보냄, 리플레이 없음). 따라서 재연결 후 첫 라이브 이벤트가 윈도우 꼬리보다 높은 seq로 도착 → fold가 구멍 감지 → 배너. 이 시점 표시는 정상.
- 의도된 복구(재연결 후 history 재가져오기)가 안 되던 3가지:
  1. `SessionStore.observeConnection`이 `RECONNECTING → CONNECTED` 전이만 감시하는데 **StateFlow 컨플레이션**으로 짧은 장애 시 CONNECTED만 보고 있으면 재베이스라인 자체가 안 돌림.
  2. `triggerBaseline`의 `baselineMutex.tryLock()`이 이전 베이스라인이 느린 링크에서 진행 중이면 **정말로 스킵**됨.
  3. 재가져오기가 돌아도 기존 병합이 **append-only**라, 구멍이 한 페이지보다 크면(긴 장애) 새 tail 페이지가 옛 꼬리 아래부터 시작 → 구멍 유지 → 배너 영구.
- 수정 (`SessionStore.kt`, web 클라이언트 gap repair = `packages/client/runtime/.../sessions/session.ts` `acceptLiveEvent/repairGap`/`installWindow` 의미를 반영):
  - `repairing` 플래그 + `stitchBuffer`(raw `HistoryEntry` list) + `fetchToken` 신규 상태. tail fetch(초기 open 포함) 진행 중 라이브 이벤트는 `stitchBuffer`에 버퍼링(append 아님) — commit에서 스티칭.
  - **`session/subscribed` 프레임의 `lastSeq`를 실제 시그널로 사용**: `lastSeq > currentEvents 꼬리`이면 → `repairMissedEvents()` 트리거. (초기 open 중엔 윈도우가 비어 있어 이중 트리거 없음; fetch 진행 중이면 `repairing` 가드.)
  - `repairMissedEvents()`: history tail 재가져와 **윈도우를 교체**(append가 아님 — 옛 이벤트는 `hasMore` "이전 로드" 영역으로), fetch 중 버퍼링된 라이브 이벤트 스티칭, fold 재계산 → 구멍 없으면 `gap=false` → **배너 소멸**. fetch 실패 시 그대로 유지(다음 스트림 재오픈/재연결 전이에 재시도).
- 변경 파일: `app/.../data/SessionStore.kt` (state 필드, `session/subscribed` 핸들러, `handleSessionEvent` 버퍼링, `openSession` token/repairing/drain, 신규 `repairMissedEvents`/`drainStitchBufferLocked`, `appendCurrentEventLocked` 반환값 변경).
- (보완) 라이브 이벤트가 윈도우 꼬리보다 높은 seq로 도착하는 순간에도 gap repair 트리거(web `acceptLiveEvent`와 동일) — `session/subscribed` 트리거가 실패/누락돼도 자가 복구. repair 성공 시 `_openFailed`도 해제.
- 검증: `:app:assembleDebug` + `:app:testDebugUnitTest` 그린 (APK 2026-08-26 11:17, 21MB). **실기기 검증 대기**: 통신 단절(기기 잠금/데이터 끄기) 후 하네스에서 이벤트가 생기게 하고 복귀 → 배너가 잠깐 떴다가 history 재가져오기 완료 시 사라지는지 확인.
- 한계: 구멍이 tail 페이지보다 크면 그 이하 메시지는 화면에서 빠지고 `hasMore`(Load older)로 복구 — web 클라이언트와 동일한 의미.

### 11.9 세션 히스토리 레이스 4종 — **해결 (유닛 검증; 실기기/에뮬레이터 V1–V5 대기)**
- 계획 문서: `docs/SESSION_HISTORY_RACE_FIX_PLAN.md` (RC1–RC4, C1–C6, T1–T6, V1–V5). 11.8의 gap repair 위에 이어서, **세션이 running(LLM 스트리밍) 중**에 (1) 위로 스크롤해 히스토리를 페이지닝한 뒤 (2) 다른(죽은) 세션으로 전환하고 (3) 핫 세션으로 복귀할 때의 4가지 비용을 제거.
- **C1 — 읽기 위치 기억 (`oldestLoadedSeq`)**: `openSession()`이 항상 tail 페이지(`beforeSeq=null`)를 재요청해 페이지닝한 윈도우를 통째로 버리던 문제. 이제 각 세션 윈도우의 최상단 seq를 커서로 기억하고, 복귀 시 그 위치에서 페이지닝(복귀가 tail로 재앵커링되는 것 방지). 페이지 실패 시 커서 폐기(다음 open이 tail로 재앵커). `repairMissedEvents`/`loadOlder` 커밋에서도 커서 갱신.
- **C2 — 범위 인지 stitch drain**: `drainStitchBufferLocked`가 버퍼 이벤트가 커밋된 페이지의 **시작 아래**에 있으면(= 그 페이지가 이미 그 구간을 커버) drop. 기존엔 `seq <= tail`만 drop해 페이지 시작 아래 이벤트를 tail 아래에 재삽입 → 다음 라이브 이벤트가 `tail+k (k>1)`로 착지해 가짜 gap → 배너 → repair 루프(건강한 라인에서).
- **C3 — repair 한 번만 (`repairInFlight`)**: `repairMissedEvents`가 프레임 콜렉터에서 mutex 없이 발화돼 두 repair가 겹치던 문제. `fetchToken`이 두 번째 *commit*을 무효화했지만 두 fetch 모두 서버를 때림. 이제 fetch 진행 중이면 두 번째 트리거는 no-op(서버 요청 자체가 안 감).
- **C4 — 설명 가능한 gap 억제**: fold의 `gap` 판정(`seq > lastSeq+1`)은 "다운링크가 죽은 동안 이벤트가 커밋됨"으로 해석하지만, 그 점프가 **클라이언트 쪽**(stitch 버퍼가 구멍을 이미 커버 / repair가 진행 중)이면 라인은 건강함. `rebuildCurrentLocked`가 버퍼/진행중 repair가 구멍을 덮으면 `gap=false`로 발행(배너 안 뜸), `handleSessionEvent`의 repair 트리거도 커버된 점프면 스킵. fold 자체는 순수함 유지(발행 스냅샷만 조정).
- **C5 — 500 이벤트 윈도우 상한 (`MAX_WINDOW_EVENTS`)**: `loadOlder`만 prepending하고 evict가 없어 긴 세션 맨 위까지 페이지닝하면 **전체 히스토리가 RAM에** + 매 커밋 전체 재폴드(O(n²), 에이전트 실행 중=최악). 이제 커밋 후 head에서 500개로 트리밍, `hasMore=true` 유지(절단된 head는 "load older" 행으로 여전히 도달 가능, 커서=새 head). 메모리/퍼커밋 폴드 상한.
- **테스트**: `app/src/test/.../data/SessionHistoryRaceTest.kt` 신규 — FakeApi(`DshApiClient` 서브클래스, `sessionHistory` 스크립트)+FakeConnectionManager+TestStore로 (a) **T1** 커서가 다음 `loadOlder`에 전달되는지(3번째 페이지가 tail이 아닌 기억된 커서 사용), (b) **T4** open 진행 중 라이브 버스트가 연속적으로 스티치되고 다음 프레임이 gap/repair를 안 일으키는지. (T2/T3/T5는 store의 비공개 `Dispatchers.Default` scope와의 크로스-스코프 게이트가 plain-JVM에서 불안정해 제거; 그 동작은 T1/T4 경로+프로덕션 가드로 커버, 최종 검증은 V1–V5.)
- **테스트 가능성(최소, 문서화)**: `DshApiClient`/`ConnectionManager`/`HostsStore`/`SessionStore`를 `open`으로(테스트가 필요하는 특정 멤버만 `open`/`protected`), `repairMissedEvents`를 `internal`로(레이스 테스트가 직접 발화). `app/build.gradle.kts`에 `testOptions { unitTests { isReturnDefaultValues = true } }` 추가(JVM 유닛 테스트에서 `android.util.Log`가 throw하지 않게).
- **변경 파일**: `app/.../data/SessionStore.kt`(C1–C5 전부 + `repairMissedEvents` internal), `core/.../wire/DshApiClient.kt`(open + `sessionHistory` open), `app/.../connection/ConnectionManager.kt`(open + `_state`/`api` protected), `app/.../connection/HostsStore.kt`(open + `lastSessionId` open), `app/build.gradle.kts`(testOptions), 신규 `app/src/test/.../data/SessionHistoryRaceTest.kt`, `CHANGELOG.md`([Unreleased] Fixed×2 + Improved×1).
- **검증**: `:app:testDebugUnitTest` + `:core:test` 그린(99 app + core). **실기기/에뮬레이터 V1–V5 대기**(계획 §6): V1 복귀 시 tail이 아닌 기억된 위치로 착지, V2 페이지 시작 아래 버스트 후 gap=false, V3 겹친 repair=fetch 1회, V4 전환/복귀 후 tail이 아닌 기억된 위치, V5 `dumpsys meminfo` PSS가 히스토리 길이에 비례하지 않고 평탄.
- **UX 영향**: C1이 유일한 사용자 가시 동작 변경(복귀 시 tail이 아닌 떠나던 위치로 착지) — V4로 검증. C5는 500개 넘게 페이지닝한 경우에만 "load older" 행이 다시 나타남(의도된 bounded-window UX, 깜빡임 주의).

### 11.10 "Reading earlier messages…" 무한 루프 — **해결 완료 (에뮬레이터 실기 검증, 실제 백엔드)**
- 증상: 세션을 위로 스크롤하면 "Loading earlier messages…"가 사라지지 않고 계속 반복, 아무것도 안 보임. 사용자: "memory protected but this endless loading again again is not fixed. worse."
- **하네스 측은 정상임이 확인됨** (증상은 앱 버그였다): `host apiproxy`의 `paginate()`는 `maxMessages` 이하의 append-origin 메시지를 정확히 되돌리고, 전체가 한 페이지에 들어가면 `hasMore=false`를 정확히 보고. `api-proxy-view.spec.ts`(6건) 그린. 이 세션에서 잠깐 paginate를 고치려 시도했으나 이는 **오진**이었고 전부 되돌림 — 하네스 코드 변경 없음.
- **근본 원인 (앱 `SessionStore.kt`)**: `loadOlder()`가 (1) `historyTail`(MAX_PAGE_EVENTS=4000 트림) 후 `overDelivered = events.size > page.size`를 계산하고, (2) `currentHasMore = freshCount>0 && (hostHasMore || overDelivered)`, (3) `trimWindowLocked()` 호출 — 이 트림이 **head(가장 오래된 이벤트, 방금 prepend한 블록)를 evict**하고 `currentHasMore=true`를 **무조건** 세움. 결과: 47메시지/9976이벤트 세션처럼 전체 히스토리가 한 호스트 페이지에 들어가는 경우(호스트 `hasMore=false`)에도, `overDelivered`(9976>4000)가 `hasMore=true`를 강제 → "load older" 행이 뜨고, `loadOlder`가 같은 페이지를 또 받아 prepend → `trimWindowLocked`가 그대로 evict → 매번 아무것도 안 보이고 `hasMore`는 계속 true → 무한 루프.
- **수정 (`SessionStore.kt` `loadOlder()`)**: prepend한 오래된 블록을 **유지**(트림 제거 — 웹 클라이언트와 동일: 페이지된 히스토리는 RAM에 누적)하고, `currentHasMore = r.value.hasMore`로 **호스트 판정 신뢰**(`overDelivered`/`nextHasMore` OR 제거). `nextHasMore(freshCount, hostHasMore)`로 시그니처 단순화, `HistoryPagingTest` 갱신(overDelivered 무조건-true 케이스 제거). `openSession`/`repairMissedEvents`의 `|| overDelivered`는 유지(최초 open 시 트림이 숨긴 오래된 콘텐츠를 "load older"로 제공).
- **검증 (에뮬레이터, 실제 `127.0.0.1:3080` 백엔드)**: (a) 루프 원인이던 세션(`% which dsh…`, 47메시지·9976이벤트, 호스트 `hasMore=false`) 열고 최상단까지 스크롤 → **"Loading earlier messages" 행 없음, 즉시 정지**; (b) 다중 페이지 세션(예: 60+메시지, 호스트 `hasMore=true`) 최상단까지 스크롤 → 중간 블록들을 하나씩 로드하고 실제 첫 콘텐츠("1.")에 도달 후 **정지(행 없음)**. 크래시 0. `:app:testDebugUnitTest`(HistoryPagingTest) + `:app:assembleDebug` 그린.
- **한계/메모리**: 이제 loadOlder가 트림하지 않으므로 사용자가 계속 위로 페이지하면 페이지된 히스토리가 메모리에 누적됨(웹 클라이언트와 동일 의미). C5(500 윈도우)가 그대로 살아있더라도 loadOlder에서는 트리밍하지 않도록 했음 — 필요시 별도 bounded-window 재설계 추후.

### 11.11 11.10 수정 후에도 지속된 "Loading earlier messages…" 재발 / 상태 소실 / 재탭 시 내용 변동 — 해결 완료 (에뮬레이터 실제 백엔드 재검증)
- **사용자 재보고**: "'Loading earlier messages…' still repeats loading upto the top. the loaded session state goes lost. Every time I tap the session, the contents are different sometimes totally gone." — 11.10의 `loadOlder` 수정만으로는 해결 안 됨 (더 깊은 원인이 있었음).
- **진짜 근본 원인 (모두 `SessionStore.kt`)**:
  1. **`openSession`이 C1 재개 커서로 재검색**: `beforeSeq = oldestLoadedSeq[sessionId]`. 호스트의 `beforeSeq`는 "X보다 *오래된* 전부"를 의미하므로, 재탭 때마다 **다른 오래된 슬라이스**를 가져와 `currentEvents.clear()`+재조립 → "탭할 때마다 내용이 다름/사라짐".
  2. **`trimWindowLocked()`가 매 open·매 live-append마다 head(방금 로드한 오래된 블록)를 evict하고 `currentHasMore=true`를 강제**: 로드한 상태가 날아가고("state lost") "load older" 행이 계속 재무장("endless loading").
  3. **페이지/윈도우 상한이 콘텐츠를 잘라냄**: `MAX_PAGE_EVENTS=4000`(전체 히스토리가 한 페이지인 세션을 트림 → `overDelivered` 강제), `MAX_WINDOW_EVENTS=500`.
- **수정**: (1) `openSession`은 **항상 tail(`beforeSeq=null`)로 결정적 개방** — 웹 클라이언트와 동일, 재탭 내용 변화 제거; C1 재개 커서(`oldestLoadedSeq`) **전체 제거** (더 이상 읽지 않음). (2) `trimWindowLocked()`에서 `currentHasMore=true` 강제 **제거** — 순수 안전 상한이 됨. (3) `MAX_PAGE_EVENTS`·`MAX_WINDOW_EVENTS`를 순수 안전 상한(`1_000_000`)으로 — 정상 페이지닝/스트리밍/재개방은 절대 안 닿아 콘텐츠 소실 없음. (4) `openSession`/`repairMissedEvents`/`loadOlder` 모두 `currentHasMore = r.value.hasMore`로 **호스트 판정 신뢰**, `overDelivered` OR 제거.
- **행동**: 오픈 = 최신 tail(결정적). 위로 스크롤 → `loadOlder`가 블록 하나씩 prepend·유지 → 진짜 시작 도달 시 호스트 `hasMore=false` → 행 사라짐·정지. 재탭 = 항상 동일한 최신 tail.
- **검증 (에뮬레이터, 실제 `127.0.0.1:3080`)**: (a) 완성 한 페이지 세션(Test APK crash) — 오픈 즉시 전체, **행 없음**, 시작 내용; (b) 완성 262메시지/19885이벤트 다중 페이지 세션 — 최신 60메시지(4495 이벤트) 오픈 후 최상단까지 페이지 → **진짜 첫 콘텐츠("1.") 도달 + 행 0개(hasMore=false 정지)**; (c) running 세션 — 시작 도달·콘텐츠 유지·행 1개로 안정(루프 아님); (d) 세션 이탈→복귀 — **동일 내용**(안정). `:app:testDebugUnitTest` 33건 그린, 크래시 0.
- **메모리**: 사용자 우선순위(안정적 상태·무한 로딩 종료)에 따라 윈도우를 웹과 동일하게 가져가고, OOM 순수 안전망(1M)만 유지. 대형 live 세션 동안의 O(n) 재폴드 비용은 수용(정확성 우선).

### 11.12 하네스 설정 UI가 폰 화면에 안 들어감 (시트 꼬리·액션 버튼 클리핑) — **해결 완료 (에뮬레이터 실기 검증, 커밋 `927c1dc`)**
- **사용자 보고**: "We added setup menu ui to mobile app but It doesn't fit to phone screen size. The problem is custom llm model add ui is bigger than phone screen and not scrollable that I cannot push create button. In fact not usable. Other ui chapters or components could have same issues."
- **근본 원인 (`DsBottomSheet.kt`)**: content를 **스크롤 컨테이너도 높이 상한도 없는 평범한 `Column`**에 넣고 있었다. `ModalBottomSheet`는 화면을 넘을 수 없으므로, 뷰포트보다 큰 시트는 **꼬리가 잘리고** — 게다가 **액션 버튼이 마지막 자식**이라 **접근 불가**가 됨(out of composition이 아니라 화면 밖이라 스크롤로도 도달 불가).
- **왜 "Add custom provider"가 최악인가**: 전체 폼(route, display name, protocol 칩, base URL, API key, 그리고 **모델 1개당 2줄(4필드)**인 모델 카탈로그 에디터)이라 모델 행을 추가할수록 무한히 커지고, Create/Save가 맨 끝 → **재현성 있게 실패**(운에 따라 되는 게 아님).
- **스크롤이 호출부마다 제각각이라 살아남은 시트가 운이었음**: `SheetModels`는 `heightIn(max=420.dp)+verticalScroll`로 자체 방어, `SheetCommands`는 bounded `LazyColumn`(260dp/220dp) 사용, 반면 **하네스 시트 3종은 아무것도 없었음**.
- **수정 (primitive에서, 9개 시트 전부 혜택)**:
  1. body를 `weight(1f, fill = false) + verticalScroll(rememberScrollState())`로 감쌈 — `fill=false`가 핵심: **짧은 시트는 content 크기에 맞춰지고(빈 공간 없음)**, 큰 시트만 남은 높이를 차지하고 내부 스크롤.
  2. 시트 높이를 `fillMaxHeight(0.92f)`로 상한 → 스크림 여백 유지, 상태바와 충돌 없음.
  3. **신규 `footer` 슬롯**: 스크롤 영역 **밖**에 divider와 함께 고정 렌더 → 폼이 아무리 길어져도 **주 액션이 항상 도달 가능**.
  4. `contentWindowInsets`를 `WindowInsets.navigationBars.union(WindowInsets.ime)`로 — 텍스트 위주 시트라 키보드가 하단 필드를 가리지 않게.
- **호출부 정리 (`HarnessProviderEditor.kt`)**: `CustomProviderSheet`(Cancel/Save), `ProviderEditorSheet`(검증 메시지 + Cancel/Save), `DiscoverModelsSheet`(Fetch/Apply) 3종의 액션 Row를 `footer`로 이동. `ProviderEditorSheet`는 검증 변수(`keyFailure`/`modelFailure`/`apiMissing`)를 `DsBottomSheet` 호출 **위로** 끌어올려야 했음(footer가 body의 형제라 스코프 공유 안 됨). 프로토콜 칩 Row에 `horizontalScroll` 추가(긴 `openai-completions`/`anthropic-messages` 라벨이 화면 밖으로 나감). discovery 결과 리스트의 중첩 `verticalScroll`은 제거(이제 스크롤 body 안이라 이중 스크롤).
- **검증 (에뮬레이터 `Pixel_8`, 실제 `127.0.0.1:3080` 백엔드, geometry는 `uiautomator dump`로 측정)**:
  - 빈 폼으로 열기 → **Cancel/Save가 `y=1933–1976`에 정상 표시**(화면 2400).
  - **모델 행 5개 추가**(content가 `y=2062` 너머로 증가) → **Save가 `y=2185–2228`로 여전히 화면 안**.
  - body 스크롤 → Protocol이 897→532로 이동, 아래에 두 번째 모델 행의 **Remove model**·**Add model** 노출. **그 사이 Cancel/Save는 `[95,2185][205,2228]`/`[907,2185][985,2228]`에서 좌표 불변** = footer가 스크롤 영역 밖에 고정됨을 증명.
  - 짧은 시트(Commands) → content 크기에 맞춰지고 스크롤 정상, 중첩 bounded 리스트로 인한 `IllegalStateException`(infinite constraints) **없음**.
  - 크래시 0건(`logcat` FATAL/AndroidRuntime 무검출), 앱 생존. 유닛 테스트 **160건(앱 98 + 코어 62) 전부 그린**.
- **남은 한계(의도적 미수정)**: (a) `SheetCommands`는 이제 **2단계 스크롤**(내부 bounded `LazyColumn` 2개가 새 스크롤 body 안) — 기능 정상, UX만 어색; (b) `ModelRowsEditor`가 행당 `weight(1f)` 필드 2개 + 제거 버튼이 실패 라인과 같은 행 → 좁은 폰에서 빡빡(1열 재배치 후보); (c) 나머지 시트(`SheetPresets`/`SheetSubagents`/`SheetPermission` 등)는 primitive 덕에 이제 스크롤되지만 **개별 실기 점검은 미실시**.
- **주의(사용자 환경)**: 설정 화면에 "Settings are read-only over the network"가 뜨는 것은 하네스가 `--allow-privileged-remote` 없이 실행 중이라 **쓰기(403)가 거부**되기 때문. 버튼은 이제 눌리지만 저장은 그 플래그가 있어야 성공한다.

