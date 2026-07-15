# [이슈] 위젯 트리거 — 질문에서 1b 위젯 부르기

- **작성일**: 2026-07-15 · **담당**: 신규 개발자 · **기한**: 이번 주(금)
- **짝 이슈**: [#12](./2026-07-09-widgets-backend-issue.md) — 위젯 **값을 만드는 쪽**(1b 빌더 4종)은 그쪽에서 병행 진행 중
- **상태**: 경계 확정. §4 시그니처만 미확정(7/15 중 확정 후 이 문서 갱신)

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
| "쿼터 얼마나 썼어?" | `quotaGauge` | ❌ **트리거 없음** |
| "인스턴스 상태 분포" | `statusDonut` | ❌ **트리거 없음** |
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

| | 파일 | 담당 |
|---|---|---|
| **부르는 쪽 (이 이슈)** | `resource/domain/ResourceExtraction.kt` (갈래 추가) | **너** |
| | `resource/application/ResourcePrompts.kt` (프롬프트·스키마) | **너** |
| | `resource/application/LlmMetricQueryExtractor.kt` (새 갈래 파싱) | **너** |
| | `resource/application/DefaultResourceService.kt` (`when` 분기 → 빌더 호출) | **너** |
| **값 만드는 쪽 (#12)** | `resource/application/WidgetBuilder.kt` (빌더 내부) | 우리 |
| | `PrometheusClient` / `HttpPrometheusClient` (쿼리) | 우리 |

접점은 **`WidgetBuilder` 메서드 시그니처 하나**다. 그 외엔 파일이 안 겹친다.

---

## 4. ⚠️ 계약 (미확정 — 7/15 중 확정)

**지금 1b 빌더는 인자를 안 받고 목업을 반환한다:**

```kotlin
fun quotaGauge(warnPercent: Int, critPercent: Int): QuotaGaugeWidget   // 목업
fun statusDonut(): StatusDonutWidget                                    // 목업
fun thresholdBanner(): ThresholdBannerWidget                            // 목업
fun projectUsageBar(warnPercent: Int, critPercent: Int): ProjectUsageBarWidget  // 목업
```

**실값이 붙으면 시그니처가 바뀐다.** 1a는 서비스가 Prometheus를 조회해 결과를 빌더에 **넣어주는** 패턴이기 때문이다(`metricRank(query, samples, promql, unit, warn, crit)`). 1b도 같은 패턴을 따르면 `samples`류 인자가 붙는다.

→ **확정 전까지는 현재 목업 시그니처 그대로 호출해도 된다.** 목업이 곧 통합 스텁이라, 값 만드는 쪽을 안 기다리고 배선·테스트를 먼저 할 수 있다. 확정되면 이 문서 §4를 갱신하고 알려준다. 호출부 인자만 바뀌지 구조는 안 바뀐다.

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
