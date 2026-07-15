# [이슈] 챗봇 집계 응답을 웹 위젯으로 — 백엔드

- **작성일**: 2026-07-09 · **관련 설계**: [`2026-07-09-chatbot-aggregation-widgets-design.md`](./2026-07-09-chatbot-aggregation-widgets-design.md) · **Phase 2**: [`2026-07-09-widgets-phase2-dashboard-issue.md`](./2026-07-09-widgets-phase2-dashboard-issue.md)
- **상태**: 설계 확정 + 실 검증 완료 + **1a 동작 코드가 main에 있음**(레퍼런스). 남은 건 1b.

> 이 문서는 **목표·검증된 사실·계약**만 준다. 코드를 어떻게 쓸지는 당신(과 당신의 AI) 방식대로.
> 백엔드 전체 흐름·Prometheus 설명은 [`README.md`](../../../README.md)에 있으니 그걸 먼저 읽으면 된다.

---

## 1. 뭘 만드나

지금 `POST /api/chat`은 인프라 지표 질문("CPU 높은 VM")에 **평문**만 준다. 같은 답을 **웹 채팅 위젯**에서 표·바·도넛·게이지로도 보여주는 게 목표. **평문 answer는 항상 그대로 함께 반환**(Slack·접근성 폴백).

| 질문 | 위젯 |
|---|---|
| CPU/메모리/네트워크/디스크 TopN | `metric_rank` (막대 랭킹) — **1a, 이미 동작** |
| 볼륨/인스턴스 개수 | `inventory_count` (숫자 카드) — **1a, 이미 동작** |
| (자동) 연관질문 | `followups` 칩 — **1a, 이미 동작** |
| 프로젝트별 쿼터 | `quota_gauge` — 1b |
| 인스턴스 상태 분포 | `status_donut` — 1b |
| 임계 초과 요약 | `threshold_banner` — 1b |
| 프로젝트별 사용률 바 | `project_usage_bar` — 1b |
| 랭킹 행 스파크라인 | `metric_rank.spark` — 1b |

---

## 2. 시작점 (main에 이미 있는 것)

- **1a는 구현·테스트 통과 상태로 main에 있다.** `WidgetBuilder.metricRank/inventoryCount`, `FollowupBuilder`, `ChatResponse.widgets`/`followups` 배선, severity config까지. 이걸 **레퍼런스로 읽고**, 필요하면 네 방식대로 바꿔도 된다.
- **1b는 `WidgetBuilder`에 메서드만 있고 목업을 반환한다.** 각 메서드 위 `// TODO(new-dev):` 주석에 **아래 검증된 쿼리·라벨·절차가 박혀 있다.** 그걸 참고해 실제 집계로 교체하면 된다.
- 실행: **JDK 21** 필수(기본 25면 컴파일 깨짐), `.env`에 본인 `OPENAI_API_KEY` + `PROMETHEUS_URL`. `./gradlew test` / `./gradlew resourceCli`.

---

## 3. 1b 데이터 소스 (라이브 검증 완료 — 이게 이 이슈의 핵심)

추측하지 말라고 실제로 다 찍어봤다. 아래는 검증된 사실.

| 위젯 | 소스·쿼리 (검증됨) |
|---|---|
| `quota_gauge` | Prometheus openstack-exporter. `openstack_nova_limits_{vcpus,memory}_{max,used}`, `openstack_cinder_limits_volume_{max,used}_gb`. 라벨 **`tenant`=프로젝트 이름**(조인 불필요) + `tenant_id`. **메모리 단위 MB**(51200=50GB). **`max=-1`=무제한** → ratio/severity null 처리(`quotaItem`에 이미 구현됨). *cb_common 아님.* |
| `status_donut` | Prometheus `count by(status)(openstack_nova_server_status)`. 실측: **ACTIVE=116, SHUTOFF=2, ERROR=1**. cb_common 불필요. |
| `threshold_banner` | 자작. CPU 사용률 식에 임계 비교: `count( <cpu식> > {crit} )`. 임계값 = `app.resource.severity.crit-percent`. |
| `project_usage_bar` | "프로젝트별 실사용률" 단일 소스는 없음 → **쿼터 사용률(used/max)로 재정의.** quota_gauge 소스 재사용, `tenant`별. |
| `metric_rank.spark` | `/api/v1/query_range` 신규(현재 클라이언트는 instant만). 5m/30s. **실 경로에선 null 유지가 정답**(실값 위 가짜 추세선=환각). |

> 각 위젯을 자연어 질문으로 부르려면 추출기/라우터에 **의도(intent)를 추가**해야 할 수도 있다(현재는 트리거 없음). 이건 위젯 빌더와 별개 작업.

---

## 4. FE 계약 (이미 고정)

FE는 `POST /api/chat` **한 엔드포인트만** 호출하고, 응답의 `widgets`/`followups`를 렌더한다.

- 타입 규격: [`docs/prototype/chatbot-widgets/widget-contract.d.ts`](../../prototype/chatbot-widgets/widget-contract.d.ts)
- 예시 페이로드(FE 선개발용): [`docs/prototype/chatbot-widgets/mock-responses.json`](../../prototype/chatbot-widgets/mock-responses.json)
- 도메인 타입 원본: `resource/domain/Widget.kt` (main)

`widgets`/`followups`는 기본 빈 배열 → 기존 클라이언트·Slack 무영향(하위호환).

---

## 5. 지켜야 할 것 (진짜 불변식만)

1. **평문 `answer`는 항상 함께 반환.** 위젯은 시각적 보강일 뿐(Slack·스크린리더 폴백).
2. **유료 LLM 호출 늘리지 말 것.** 집계는 PromQL/SQL, 위젯은 순수 변환, 칩은 규칙 — 전부 LLM 0회.
3. **임계치·모델 등 튜닝값은 `application.yml`.** 시크릿은 `.env`(env만). 하드코딩 금지.
4. **위젯 값은 DOM/`textContent`로만 삽입**(XSS). 프론트는 서버가 준 `severity`/`display`만 신뢰(재계산 금지).

나머지(구현 방식·파일 구조·작업 순서)는 자유. 범위는 Phase 1(위 표)로 고정 — 대시보드는 Phase 2.

---

## 6. 실 검증 증거 (설계가 진짜 되는지 이미 확인함)

실 OpenAI 토큰 + 실 Prometheus로 전 지표를 태워봤다(`resourceCli`):

- CPU/메모리(ASC=bottomk)/네트워크/디스크 TopN → 실값 + 위젯 JSON 정상. 프로젝트 필터도 정상.
- "볼륨 몇 개?" → INVENTORY 라우팅 정확. "날씨?" → CLARIFY 되물음.
- 그 과정에 필터 쿼리 URI 버그를 발견·수정(현재 main 반영).

즉 **1a는 실증 완료**. 1b는 §3 소스가 전부 실재함을 확인함. 못 하는 게 아니라 채우면 되는 상태.

---

## 7. 완료 기준 (DoD)

- 1b 각 위젯이 §3 실쿼리로 실값을 렌더(무제한 `-1`, empty 상태 포함).
- 평문 answer 무변경, Slack 무영향, `./gradlew test` 그린.
- 새 튜닝값은 `application.yml`, 새 위젯 타입은 `Widget.kt` + FE 계약 반영.
- **작업 끝나면 Postman 컬렉션(v2.1 JSON)을 저장소에 남긴다.** `POST /api/chat`에 위젯별 질문을 던지는 요청 모음 — 받는 사람이 import만 하면 바로 돌려볼 수 있어야 한다. 프론트 없이 응답 JSON만으로 §4 계약(`widgets`/`followups`)이 맞는지 확인되는 게 목적. 위치는 `docs/prototype/chatbot-widgets/` 아래를 권장하고, 나머지(파일명·요청 구성·검증 방식)는 자유.

> 진행 방식은 자유지만, 참고로 우리는 **위젯 하나씩 TDD(테스트 먼저 → 구현)로** 갔고 매 위젯마다 커밋했다. 그게 편하면 그렇게, 아니면 네 방식대로.
