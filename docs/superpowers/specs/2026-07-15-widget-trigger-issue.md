# [이슈] 위젯 트리거 — 질문에서 1b 위젯 부르기

- **작성일**: 2026-07-15 · **담당**: 신규 개발자 · **기한**: 이번 주(금)
- **짝 이슈**: [#12](./2026-07-09-widgets-backend-issue.md) — 위젯 **값을 만드는 쪽**(1b 빌더 4종)은 그쪽에서 병행 진행 중
- **상태**: 경계·계약 확정. **바로 착수 가능.**
- **⚠️ 2026-07-15 범위 변경**: 일정이 당겨져 `status_donut`은 우리가 끝냈다(임시 배선 포함). 네 범위는
  **트리거 3종(`quota_gauge`·`threshold_banner`·`project_usage_bar`) + 임시 배선 대체** — **§3 먼저 읽어라.**

> 이 문서는 **목표·검증된 사실·경계**만 준다. 구현 방식은 자유.
> 백엔드 전체 흐름은 [`README.md`](../../../README.md), 위젯 설계는 [#12](./2026-07-09-widgets-backend-issue.md) 참고.

---

## 1. 뭘 만드나

`WidgetBuilder`에 1b 위젯 4종(`quota_gauge`·`status_donut`·`threshold_banner`·`project_usage_bar`)이 있는데 **아무도 안 부른다.** 지금 "쿼터 얼마나 썼어?"라고 물으면 아무 일도 안 일어난다. 값을 만드는 쪽(#12)이 끝나도 **도달 경로가 없으면 죽은 코드**다.

이 이슈는 **질문 → 어떤 위젯을 부를지 결정하고 실제로 부르는 것**까지다. 위젯 내부 구현은 안 건드린다.

| 질문 예시 | 불러야 할 것 | 현재 |
|---|---|---|
| "CPU 높은 VM" | `metricRank` | ✅ 이미 동작 |
| "볼륨 몇 개?" | `inventoryCount` | ✅ 이미 동작 |
| "인스턴스 상태 분포" | `statusDonut` | ⚠️ **임시 배선으로 동작** — §3.1 읽을 것 |
| "쿼터 얼마나 썼어?" | `quotaGauge` | ❌ **트리거 없음** |
| "임계 넘은 노드 있어?" | `thresholdBanner` | ❌ **트리거 없음** |
| "프로젝트별 사용률" | `projectUsageBar` | ❌ **트리거 없음** |

---

## 2. 시작점 (코드 읽고 확인한 사실 — 추측 아님)

**`Route`는 안 건드려도 된다.** `routing/domain/Route.kt`는 `{DOC, RESOURCE, CLARIFY}`인데, 이 4종은 전부 **RESOURCE 안**이다. 새 Route 추가는 불필요(오히려 라우터 프롬프트·스키마까지 번지니 피하는 게 좋다).

**분기는 이미 `ResourceExtraction`에서 일어난다.** `resource/domain/ResourceExtraction.kt`가 sealed class고, 지금 3갈래다:

```kotlin
sealed class ResourceExtraction {
    data class Resolved(val query: ResourceQuery) : ResourceExtraction()          // METRIC 트랙
    data class InventoryResolved(val query: InventoryQuery) : ResourceExtraction() // INVENTORY 트랙
    data class NeedsClarification(val message: String) : ResourceExtraction()
}
```

`DefaultResourceService.handle()`이 이 갈래를 `when`으로 받아 각각 `WidgetBuilder.metricRank` / `inventoryCount`를 부른다. **1b 4종도 같은 패턴으로 갈래를 늘리면 된다** — 이게 기존 구조를 따르는 길이다.

**추출기가 갈래를 정한다.** `LlmMetricQueryExtractor`(LLM) + `ResourcePrompts`(프롬프트·스키마)가 질문을 읽고 어느 `ResourceExtraction`인지 만든다. 새 의도를 태우려면 여기 케이스를 추가해야 한다.

**실행·검증 수단이 이미 있다.** `./gradlew routingCli`(라우터 단독), `./gradlew resourceCli`(질문→PromQL→결과 E2E). JDK 21 필수(기본 25면 컴파일 깨짐), `.env`에 `OPENAI_API_KEY` + `PROMETHEUS_URL`.

---

## 3. 경계 (누가 뭘 건드리나 — 이거만 지키면 안 밟힌다)

> **🔴 2026-07-15 갱신 — 읽고 시작할 것.** 일정이 당겨져서(이번 주 금요일) 우리가 `status_donut`을
> 끝까지 밀었다. 아래가 달라진 점이다. 이 문단이 최신이고, 충돌을 피하려면 여기부터 봐야 한다.

### 3.1 `status_donut`은 이미 동작한다 — 만들지 말고 **레퍼런스로 읽어라**

우리가 값·평문·배선까지 끝냈다(PR #24). 실 챗 화면에서 실 OpenAI + 실 Prometheus로 확인 완료.

- `WidgetBuilder.statusDonut()` — 실구현(목업 아님). 테스트 7건.
- `StatusAnswerTemplate` — 평문 answer. **answer 템플릿은 우리가 가져갔다**(§3.2).
- `DefaultResourceService.tempStatusDonut()` — **임시 키워드 `if`. 네가 지울 대상이다.**

**⚠️ 이 임시 배선이 네 작업과 만나는 지점이다.** `DefaultResourceService`에 `// TEMP(#21)` 주석이 붙은
블록이 있다. LLM이 아니라 **키워드 `if`로 단락**시킨 것이고, 이유는 (1) 의도 하나 붙이자고 추출기 프롬프트를
키우면 요청당 토큰이 늘고(불변식 2), (2) `ResourcePrompts`/`LlmMetricQueryExtractor`가 **네 소유라 충돌**하기
때문이다.

→ **네가 할 일**: 추출기에 "상태 분포" 의도를 제대로 넣고, 그 `TEMP` 블록과 짝 테스트
(`DefaultResourceServiceStatusTest`)를 **통째로 삭제**한 뒤 `ResourceExtraction` 갈래로 옮겨라.
키워드 매칭이라 "지금 몇 대나 죽어있어?" 같은 변형을 못 잡는 게 정확히 네가 고칠 부분이다.

### 3.2 answer 템플릿은 우리 몫

위젯마다 평문 answer가 필요한데(불변식: 항상 함께 반환) 이게 원래 어느 쪽 경계에도 없었다. 위젯과 같은
데이터를 문장으로 바꾸는 순수 변환이라 **우리가 가져간다.** `StatusAnswerTemplate`가 레퍼런스 — 숫자
드리프트를 막으려고 조회 결과를 다시 세지 않고 **위젯이 계산한 total/segments를 그대로 읽는다.**
`quota_gauge`·`threshold_banner`·`project_usage_bar`의 answer도 우리가 만든다. 너는 부르기만 해라.

### 3.3 지금 우리가 만지고 있는 파일 (건드리지 말 것)

`WidgetBuilder.kt`, `PrometheusClient`/`HttpPrometheusClient`, `Widget.kt`, `*AnswerTemplate.kt`.
`threshold_banner` 작업 중이라 이번 주 내내 움직인다.

### 3.4 그래서 네 실질 범위

`quota_gauge` · `threshold_banner` · `project_usage_bar` **트리거 3종** + `status_donut` 임시 배선을
제대로 된 의도 분류로 **대체**. 빌더 본문은 우리가 채우니 목업이어도 그냥 부르면 된다(§4).

---

### 3.5 파일 경계표

| | 파일 | 담당 |
|---|---|---|
| **부르는 쪽 (이 이슈)** | `resource/domain/ResourceExtraction.kt` (갈래 추가) | **너** |
| | `resource/application/ResourcePrompts.kt` (프롬프트·스키마) | **너** |
| | `resource/application/LlmMetricQueryExtractor.kt` (새 갈래 파싱) | **너** |
| | `resource/application/DefaultResourceService.kt` (`when` 분기 → 빌더 호출) | **너** — 단, `TEMP(#21)` 블록은 우리가 넣었다(§3.1). 지우는 것도 너. |
| **값 만드는 쪽 (#12)** | `resource/application/WidgetBuilder.kt` (빌더 내부) | 우리 |
| | `PrometheusClient` / `HttpPrometheusClient` (쿼리) | 우리 |
| | `resource/domain/Widget.kt` (위젯 타입) | 우리 |
| | `resource/application/*AnswerTemplate.kt` (평문 답변) | 우리 (§3.2) |

접점은 **`WidgetBuilder` 메서드 시그니처 하나**다. 그 외엔 파일이 안 겹친다 —
`DefaultResourceService`의 `TEMP` 블록만 예외이고, 그건 네가 지우면 사라진다.

---

## 4. 계약 (✅ 확정 2026-07-15 — main 반영됨)

**규약: 조회는 서비스가, 변환은 빌더가.** `WidgetBuilder`는 Prometheus를 의존하지 않는 순수 변환 `object`로 남는다(1a와 동일). 즉 **네가 `DefaultResourceService`에서 조회해 빌더에 넣어준다.**

```kotlin
fun quotaGauge(inputs: List<QuotaInput>, warnPercent: Int, critPercent: Int): QuotaGaugeWidget
fun statusDonut(samples: List<LabeledSample>, label: String = "인스턴스"): StatusDonutWidget
fun thresholdBanner(count: Int, critPercent: Int, offenders: List<String> = emptyList()): ThresholdBannerWidget
fun projectUsageBar(samples: List<LabeledSample>, metric: String, unit: String, warnPercent: Int, critPercent: Int): ProjectUsageBarWidget
```

**본문은 아직 목업이다**(값 만드는 쪽이 채우는 중). 시그니처는 안 바뀌니 지금 그대로 호출하면 되고, 나중에 목업이 실값으로 바뀔 뿐이다. **값 만드는 쪽을 기다릴 필요 없다.**

### 조회는 `queryLabeled()`로

```kotlin
interface PrometheusClient {
    fun query(promql: String, unit: String): List<MetricSample>   // 기존(TopN 전용)
    fun queryLabeled(promql: String): List<LabeledSample>          // 1b용 — 이걸 써라
}

data class LabeledSample(val labels: Map<String, String>, val value: Double)
data class QuotaInput(val resource: String, val used: Double, val max: Double)  // max<0 = 무제한
```

> ⚠️ **`query()`를 1b에 쓰면 무조건 빈 리스트가 온다.** `query()`는 `instance_name`/`domain` 라벨이 없는 시계열을 파싱 단계에서 버리는데(TopN 전용으로 만들어짐), 1b 쿼리 결과는 그 라벨이 없다 — `status_donut`은 `{status:"ACTIVE"}`, 쿼터류는 `{tenant:"..."}`, `threshold_banner`는 라벨 없는 스칼라. 그래서 `queryLabeled()`를 추가했다. 디버깅 때 헤매기 쉬운 지점이라 미리 박아둔다.

### 어떤 쿼리를 넣나

검증된 PromQL은 [#12 §3](./2026-07-09-widgets-backend-issue.md) 표에 있다(라이브 검증 완료). 요약하면:

| 위젯 | 쿼리 | 넘길 것 |
|---|---|---|
| `status_donut` | `count by(status)(openstack_nova_server_status)` | 결과 그대로 → `samples` |
| `threshold_banner` | `count( <CPU 사용률 식> > {crit} )` | `.firstOrNull()?.value?.toInt() ?: 0` → `count` |
| `quota_gauge` | `openstack_nova_limits_{vcpus,memory}_{max,used}`, `openstack_cinder_limits_volume_{max,used}_gb` | `labels["tenant"]`로 max/used 짝지어 `QuotaInput` |
| `project_usage_bar` | `(openstack_nova_limits_vcpus_used / openstack_nova_limits_vcpus_max) * 100` | 결과 그대로 → `samples` (tenant 라벨 보존) |

임계값 `{crit}`은 `app.resource.severity.crit-percent`에서 읽어라(하드코딩 금지).

---

## 5. 지켜야 할 것 (진짜 불변식만)

1. **평문 `answer`는 항상 함께 반환.** 위젯은 시각적 보강일 뿐(Slack·스크린리더 폴백). 새 갈래도 답변 텍스트가 있어야 한다.
2. **유료 LLM 호출을 늘리지 말 것.** 의도 분류는 **기존 추출기 1회 안에서** 끝낸다 — 갈래 판별하려고 LLM을 한 번 더 부르지 않는다.
3. **애매하면 `NeedsClarification`.** 억지로 위젯을 부르는 것보다 되묻는 게 낫다(`RoutingPolicy.applyConfidenceFloor`와 같은 철학).
4. **임계값·튜닝값은 `application.yml`.** 시크릿은 `.env`. 하드코딩 금지.
5. **기존 경로 무회귀.** "CPU 높은 VM"·"볼륨 몇 개?"가 지금처럼 동작해야 한다. `./gradlew test` 그린.

나머지(갈래 이름·프롬프트 구성·작업 순서)는 자유.

---

## 6. 완료 기준 (DoD)

- 위 §1 표의 질문 4종이 각각 해당 위젯을 반환한다(`POST /api/chat` 응답의 `widgets`에 실제로 담긴다).
- 애매한 질문은 되물음. 기존 METRIC·INVENTORY 경로 무회귀.
- 평문 answer 무변경, Slack 무영향, `./gradlew test` 그린.
- **Postman 컬렉션(v2.1 JSON)을 저장소에 남긴다.** 위 질문들을 `POST /api/chat`에 던지는 요청 모음 — import만 하면 바로 돌려볼 수 있어야 한다. 위치는 `docs/prototype/chatbot-widgets/` 아래 권장. (#12 DoD와 동일 산출물이니 **하나로 합쳐도 된다** — 조율할 것)

---

## 7. 이번 주 범위 밖 (건드리지 말 것)

- **`metric_rank.spark`** — range 쿼리 시계열. #12에서도 이번 주 제외. `null` 유지가 정답.
- **`resource_dashboard`(Phase 2 / [#14](./2026-07-09-widgets-phase2-dashboard-issue.md))** — 프론트 렌더러가 아예 없어서(`dispatch.js`에 6종만 등록, `resource_dashboard`는 `null` 반환이 의도된 동작) 백엔드만 만들어도 화면에 안 나온다. 다음 주 이후.
- 위젯 빌더 내부 구현(#12 담당).
