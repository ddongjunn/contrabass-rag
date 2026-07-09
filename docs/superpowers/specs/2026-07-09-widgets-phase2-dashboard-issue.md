# [이슈] Phase 2 — 요약 대시보드 위젯 `resource_dashboard`

- **작성일**: 2026-07-09
- **담당**: 백엔드
- **선행**: **Phase 1 위젯 이슈 완료 후 착수** → [`2026-07-09-widgets-backend-issue.md`](./2026-07-09-widgets-backend-issue.md)
- **설계 근거**: 마스터 설계 §3(Phase 2)·§5.3·§13.4
- **상태**: 설계 방향만 확정, 상세 대기 (Phase 1이 토대를 만든 뒤 구체화)

---

## 0. 뭘 만드나 (사람이 읽는 설명)

Phase 1은 **질문 하나 → 위젯 하나**(예: "CPU 높은 VM" → 랭킹 표)였다.
Phase 2는 **"지금 인프라 요약해줘" 한 방에 여러 지표를 묶은 대시보드 카드 하나**를 준다.

예) 사용자: "전체 상황 요약해줘"
→ 카드 하나에:
- **KPI 타일**: 가동 인스턴스 128/140, 임계 초과 노드 2대
- **지표 바**: CPU 72%(WARN), 메모리 64%(GOOD), 디스크 …

즉 Phase 1 위젯들의 **종합 뷰**다. 새 질문 의도("요약")와 여러 소스를 한 번에 묶는 집계가 필요해 Phase 1과 분리한다.

---

## 1. 왜 별도 이슈인가

Phase 1과 달리 아래가 **추가로** 필요하다 (그래서 범위를 나눔 — 루트 불변식 8):

1. **새 의도 분류** — "요약" 질문을 인식해야 한다. 라우터 또는 조건추출에 `SUMMARY` 추가.
2. **크로스소스 집계** — 한 응답에 Prometheus(노드 지표·임계 초과) + 인스턴스 수(cb_common 또는 openstack)가 섞인다.
3. **고정 PromQL 세트** — 요약은 사용자 조건이 거의 없음 → 서버가 정해둔 쿼리를 돌린다(LLM 0회 유지).

---

## 2. 데이터 소스 (Phase 1 라이브 검증 재사용)

| KPI/지표 | 소스 | 비고 |
|---|---|---|
| 가동 인스턴스 수 / 상태 | `openstack_nova_server_status` (`count by(status)`) 또는 cb_common | Phase 1 `status_donut`과 동일 소스 |
| 임계 초과 노드 수 | Prometheus `count(<노드 CPU 식> > 임계)` | Phase 1 `threshold_banner` 규칙 재사용 |
| 노드 평균 CPU/메모리/디스크/네트워크 | 노드 지표 `node_*` `avg by(...)` 또는 libvirt 평균 | **고정 PromQL 세트**, 신규 |
| 쿼터 요약(선택) | `openstack_nova/cinder_limits_*` | Phase 1 `quota_gauge` 소스 재사용 |

> 노드 레벨 `node_*` 지표는 Phase 1에서 안 썼다. 실제 존재·라벨은 착수 시 라이브로 1회 확인
> (참조 백엔드 `HostMonitoringQuery`에 `node_cpu_seconds_total` 등 패턴 있음).

---

## 3. 위젯 스키마 (설계 §13.4 — 확정 아님, 착수 시 확정)

```jsonc
{
  "type": "resource_dashboard",
  "window": "5m",
  "kpis": [
    { "label": "가동 인스턴스", "value": "128", "sub": "/ 140", "chip": "91% 정상", "chipLevel": "GOOD" },
    { "label": "임계 초과 노드", "value": "2",   "sub": "대",    "chip": "CPU 85%↑", "chipLevel": "CRIT" }
  ],
  "metrics": [
    { "label": "CPU",    "value": 72, "unit": "%", "severity": "WARN" },
    { "label": "메모리", "value": 64, "unit": "%", "severity": "GOOD" }
  ]
}
```
- `Widget` sealed에 `ResourceDashboardWidget` + `@JsonSubTypes`에 `"resource_dashboard"` 추가.
- severity·chipLevel·display는 Phase 1과 동일하게 **서버가 계산**.

---

## 4. 백엔드 작업 (Phase 1 토대 위에)

- `routing` 또는 `resource` 추출에 **`SUMMARY` 의도** 추가 (라우터 few-shot / json_schema 확장).
- `resource/application/DashboardService`(신규): 고정 PromQL 세트 + 크로스소스 조회 → `WidgetBuilder.resourceDashboard(...)`.
- 고정 PromQL 세트를 `application.yml`로 소유(하드코딩 금지).
- `DefaultResourceService`(또는 전용 핸들러)에서 SUMMARY 분기 → `ResourceService.Result(widgets=[dashboard])`.
- Phase 1의 `WidgetBuilder`·`MetricValueFormatter`·severity config **재사용**.

## 5. 하지 말 것 (불변식)

- LLM 추가 호출 금지(요약도 고정 쿼리 + 순수 변환).
- 평문 `answer` 항상 동반(대시보드도 텍스트 요약 함께).
- Phase 1 위젯·계약을 깨지 말 것(순수 추가).

## 6. DoD

- "전체 요약" 류 질문 → `resource_dashboard` 위젯 1개 + 평문 요약.
- KPI(인스턴스 수·임계 초과)와 지표 바가 라이브 값으로 렌더.
- `DashboardBuilderTest`(순수함수) + 직렬화 round-trip + 고정 PromQL 스냅샷 테스트.

## 7. 미결정 (착수 시 확정)

- [ ] SUMMARY 의도를 **라우터**에 둘지 **조건추출**에 둘지.
- [ ] 노드 평균 지표를 `node_*`로 할지 libvirt 평균으로 할지 (라이브 확인 후).
- [ ] KPI 항목 최종 세트(인스턴스/노드/쿼터 중 무엇을 노출).
