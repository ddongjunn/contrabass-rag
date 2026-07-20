# 챗봇 위젯 사내 포털 임베드 설계

- **작성일**: 2026-07-20
- **대상 저장소**: `contrabass-rag`(ragbot-server, `chat-widget.js`) + `remote-contrabass-admin`(사내 포털 프론트)
- **상태**: 설계 확정 → 구현 계획 대기
- **관련 설계**: [`2026-07-12-chat-widget-rendering-pipeline-design.md`](./2026-07-12-chat-widget-rendering-pipeline-design.md) §12.2에서
  "외부 임베드"를 별도 스펙으로 미뤄뒀음 — 이 문서가 그 후속.

## 1. 배경 / 문제

지금 챗봇 위젯(`chat-widget.js`)은 `docs/prototype/chatbot-widgets/index.html` 같은 독립 데모 페이지에서
"URL 접속 → 런처 버튼 클릭"으로만 열린다. 실제 사내 포털(`remote-contrabass-admin`)에는 붙어있지 않다.
이 스펙은 그 포털에 위젯을 실제로 임베드하는 방법을 정한다.

**중요 발견**: `remote-contrabass-admin`은 단독 배포되는 SPA가 아니라 **Module Federation remote**다.
실제 화면은 별도 Host 레포(`hostBootFactory`/`hostMaestro`)가 그리고, 이 레포는 `ContrabassApp.vue` 같은
컴포넌트를 "제공"만 한다(`vite.config.ts` exposes). 그래서 최초 구상("포털 HTML에 `<script>` 태그 추가")은
Host 레포 팀 협조가 필요해 번거롭다. 대신 이 레포가 이미 소유한 두 훅을 재사용해 **이 레포만 고쳐서**
임베드를 완결한다:

- `useUserInfoStore()`(`src/hooks/store/useUserInfoStore.ts`) — Module Federation으로 Host의 실제
  로그인 사용자 정보(`id`, `email` 등)를 이미 로드하고 있음.
- `ProviderStore`(`src/stores/provider/ProviderStore.ts`) — 현재 선택된 프로젝트/테넌트
  (`getSelectedProjectId`, `getSelectedProjectName`)를 이미 관리 중.

## 2. 목표 / 비목표

**목표**
- 사내 포털(`remote-contrabass-admin`) 화면에 챗봇 위젯이 자동으로 뜬다(별도 URL 방문 불필요).
- Slack과 동등한 전체 기능(DOC+RESOURCE+CLARIFY)을 웹에서도 그대로 쓸 수 있다.
- 포털이 이미 아는 컨텍스트(로그인 사용자, 선택된 프로젝트/테넌트)를 챗봇이 재사용해, QUOTA/PROJECT_USAGE
  같은 질문에서 프로젝트를 안 물어봐도 되게 한다.
- 사용자별 레이트리밋이 실제 사용자 단위로 정확히 걸리게 한다(현재는 전원이 `"anonymous"` 하나를 공유).

**비목표**
- Host 레포(`hostBootFactory`/`hostMaestro`) 수정.
- `/api/chat` 인증/인가 추가 — 내부망 경계에 의존, 별도 결정 필요 시 추후 논의.
- 웹 위젯의 멀티턴 히스토리(Slack 스레드처럼 맥락 유지) — 지금 위젯도 이미 히스토리를 안 보내고 있음.
- npm 패키징/컴포넌트화, iframe 방식, 다른 포털/서비스로의 범용 확장(대상은 이 포털 1곳).

## 3. 임베드 계약

### 3.1 전역 설정 변수 (호스트 → 위젯)

`chat-widget.js`는 이미 `window.CONTRABASS_CHAT_API_BASE`(또는 `?apiBase=`) seam을 갖고 있다. 여기에
두 변수를 추가한다.

| 변수 | 용도 | 없을 때 폴백 |
|---|---|---|
| `window.CONTRABASS_CHAT_API_BASE` | 챗봇 백엔드 주소 (기존) | 빈 문자열 |
| `window.CONTRABASS_CHAT_USER_ID` | 레이트리밋·식별용 실사용자 ID | 랜덤 UUID(localStorage, 기존 동작) |
| `window.CONTRABASS_CHAT_PROJECT` | 현재 선택된 프로젝트/테넌트 ID | 미전송(생략) |

- **`CONTRABASS_CHAT_PROJECT`는 위젯이 질문 전송 시점마다 다시 읽는다** (초기화 시 한 번만 캐싱하지
  않음) — 사용자가 포털에서 프로젝트를 전환해도 다음 질문부터 반영되도록.
- 기존 데모 페이지(`docs/prototype/chatbot-widgets/index.html`)는 이 변수들을 설정하지 않으므로
  기존 동작(랜덤 UUID, project 없음) 그대로 유지된다 — 하위호환.

### 3.2 `remote-contrabass-admin` 쪽 — `ContrabassApp.vue` 자체 주입

Host 레포를 건드리지 않기 위해, 위젯 마운트/스크립트 삽입을 `ContrabassApp.vue`의 `onMounted` 훅
(현재 613~644줄 근처)에서 직접 수행한다.

```ts
// setup 시점에 이미 존재하는 훅/스토어를 그대로 재사용 (신규 아님)
const userInfoStore = useUserInfoStore();
const providerStore = useProviderStore();

onMounted(() => {
  if (document.getElementById("contrabass-chat")) return; // 중복 마운트 방지

  window.CONTRABASS_CHAT_API_BASE = RAGBOT_API_BASE; // 배포 환경변수/설정으로 주입
  window.CONTRABASS_CHAT_USER_ID = userInfoStore.id ?? userInfoStore.email;
  window.CONTRABASS_CHAT_PROJECT = providerStore.getSelectedProjectId;

  const script = document.createElement("script");
  script.type = "module";
  script.src = `${RAGBOT_API_BASE}/chat-widget/chat-widget.js`;
  document.body.appendChild(script);
});

watch(
  () => providerStore.getSelectedProjectId,
  (next) => { window.CONTRABASS_CHAT_PROJECT = next; },
);
```

- `chat-widget.js`가 스스로 `#contrabass-chat` 컨테이너와 위젯 마크업을 코드 내부에서 생성해
  `document.body`에 붙이도록 개선한다 (현재는 호스트 페이지가 `<div id="contrabass-chat">` +
  `<template id="cc-chrome">...</template>`를 직접 들고 있어야 함 — 이 결합을 없앤다).
- Module Federation으로 `ContrabassApp.vue`가 여러 번 mount/unmount될 수 있으므로 중복 주입 방지
  가드(`document.getElementById` 체크)가 필수.

### 3.3 백엔드 변경 (`ragbot-server`)

**a. `ChatRequest`에 `project` 필드 추가**

```kotlin
data class ChatRequest(
    val question: String,
    val userId: String? = null,
    val project: String? = null,
)
```

`ChatCommand`까지 그대로 실어 보낸다.

**b. RESOURCE 추출 결과의 fallback으로 사용**

`MetricQueryExtractor`가 질문 문장에서 project를 못 찾았을 때만 `ChatCommand.project`로 채운다
(질문에 명시된 프로젝트가 있으면 그게 우선). QUOTA/PROJECT_USAGE에서 "우리 프로젝트 얼마나 썼어?" 같은
질문이 지금처럼 CLARIFY로 되묻지 않고 포털 컨텍스트로 바로 답이 나가게 된다.

**c. CORS 설정 추가**

`application.yml`에 `app.cors.allowed-origins` 리스트를 추가(루트 불변식 7 — 하드코딩 금지)하고,
`/api/chat`에만 적용하는 `CorsConfigurationSource` 빈을 하나 추가한다. 기본값(미설정)이면 지금처럼
CORS 헤더 없음 → 안전한 기본값 유지.

**d. 레이트리밋**

코드 변경 없음 — `userId`가 포털의 실사용자 ID로 채워지면 자동으로 사용자별 정확한 제한이 걸린다
(`Resilience4jRateLimitGuard`, `app.guard.rate-per-min`).

## 4. 데이터 흐름

1. 사용자가 `remote-contrabass-admin` 화면 진입 → `ContrabassApp.vue`의 `onMounted`가
   `userInfoStore`/`providerStore`에서 컨텍스트를 읽어 `window` 전역에 세팅 → `chat-widget.js`를
   동적으로 삽입.
2. 사용자가 위젯을 열고 질문 입력 → `chat-widget.js`가 전송 시점에 `window.CONTRABASS_CHAT_USER_ID`/
   `PROJECT`를 다시 읽어 `POST /api/chat` 바디에 담음.
3. `ragbot-server`: CORS 통과 → `ChatController` → `ChatCommand`(project 포함) →
   `DefaultChatService` → 라우터(DOC/RESOURCE/CLARIFY).
   - RESOURCE 경로: `MetricQueryExtractor`가 추출한 project가 없으면 `ChatCommand.project`로 채움 →
     이후 파이프라인 동일.
4. 응답(`answer`+`sources`+`widgets`+`followups`) → `chat-widget.js`가 렌더링(기존 렌더 파이프라인,
   `2026-07-12` 스펙 그대로).

## 5. 에러 처리

- **CORS 미허용 origin**: 브라우저가 자체 차단(백엔드 로그엔 안 남음) → 배포 전 `allowed-origins`를
  실제 서빙 도메인과 맞춰야 함(§7).
- **`userInfoStore`/`providerStore` 값이 아직 로딩 전인 race condition**: `id`/`project`가
  `undefined`면 위젯은 기존 폴백(랜덤 UUID, project 없음)으로 조용히 동작 — 에러로 죽지 않고 저하.
- **위젯 스크립트 자체 로드 실패**(네트워크 문제): 동적 `<script>` 삽입 실패는 포털 나머지 기능과
  격리되어 있어 포털 자체엔 영향 없음.

## 6. 테스트 방법

- **백엔드**: `ChatRequest.project` 직렬화 테스트, CORS 헤더 존재 여부 테스트(허용/비허용 origin 각각),
  `MetricQueryExtractor`의 project fallback 단위 테스트(질문에 명시 vs 없음 vs 컨텍스트로 채움).
- **`remote-contrabass-admin`**: `onMounted`가 중복 마운트를 막는지, `providerStore.getSelectedProjectId`
  변경 시 `watch`가 `window.CONTRABASS_CHAT_PROJECT`를 갱신하는지 컴포넌트 테스트.
- **수동 E2E**: `remote-contrabass-admin` 개발 서버 + `ragbot-server`(로컬 또는 VM)를 붙여서 실제
  로그인 후 위젯을 열고 질문 → 프로젝트 전환 시 컨텍스트가 반영되는지, 레이트리밋이 사용자별로 걸리는지
  직접 확인.

## 7. 열린 리스크 (배포 전 확인 필요)

- `remote-contrabass-admin`이 뜬 pod ↔ `ragbot-server` VM 간 실제 네트워크 도달 가능성 — 정확한
  네트워크 구조가 확인되지 않았음(인프라팀 확인 필요). **고정 IP만으로는 부족** — VM 앞단
  방화벽/보안그룹에서 ragbot-server 포트(기본 8080) 인바운드가 열려 있어야 함.
- Host(`hostBootFactory`/`hostMaestro`)가 실제로 서빙하는 도메인이 정확히 뭔지 → 그 도메인을 CORS
  `allowed-origins`에 정확히 넣어야 함. **로컬 개발 중에는** 포탈 dev 서버 origin(예:
  `http://localhost:5173`)도 같은 리스트에 추가해야 로컬 ↔ VM(또는 로컬 ↔ 로컬) 조합으로 테스트 가능.
- `ragbot-server`가 HTTPS로 서빙되는지 — 포털이 HTTPS라면 혼합 콘텐츠 문제 방지를 위해 챗봇 API도
  HTTPS 필요.
- **로컬 E2E는 이미 가능**: `docker-compose.yml`(로컬 pgvector) + `.env` + `SLACK_BOT_TOKEN=`/
  `SLACK_APP_TOKEN=` 빈 값(Socket Mode 비활성, README 기존 문서화된 방법)으로 `./gradlew bootRun` 후
  `window.CONTRABASS_CHAT_API_BASE="http://localhost:8080"`으로 로컬 포탈과 바로 붙여 테스트할 수
  있음. DNS 불필요 — IP:포트 그대로 사용.

## 8. 검토했던 다른 임베드 방식 (선택 안 함)

- **iframe 임베드**: 위젯을 별도 페이지로 서빙 후 `<iframe>`으로 삽입, `postMessage`로 컨텍스트 전달.
  `chat-widget.js`가 이미 Shadow DOM으로 격리돼 있어 iframe의 격리 이점이 중복되고, 플로팅 런처 UX를
  iframe으로 구현하면 크기 조절이 더 복잡해짐 → 선택 안 함.
- **Host 레포에 마크업 복사**: 최초 구상이었으나 Module Federation 구조가 드러나며 Host 팀 협조가
  필요해져 폐기. `ContrabassApp.vue` 자체 주입(§3.2)으로 대체.
- **npm 컴포넌트 패키징**: 대상이 포털 1곳뿐이라 오버엔지니어링으로 판단, 선택 안 함.

## 9. 후속 (이번 스펙 밖)

- `/api/chat` 인증/인가 도입 여부 — 내부망 경계만으로 충분한지 보안팀과 별도 논의.
- 웹 위젯 멀티턴 히스토리 지원(Slack 스레드 맥락처럼) — 필요해지면 별도 스펙.
