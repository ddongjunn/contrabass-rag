# 실존 메트릭 기반 RESOURCE target 재편 설계

> 정책: **exporter에 실존하는 메트릭만 지원한다.** 모든 후보는 2026-07-24 실측(라벨·값·query_range)을 통과한 것만 채택.
> 실측 환경: PROMETHEUS_URL(10.255.40.10:11909), 총 1165개 메트릭 (ceph 832 · node 62 · libvirt 27 · openstack 26 · …).

## 1. 제거 — PROJECT_USAGE

`openstack_nova_limits_*` 의존인데 이 환경 limits 계열 **0건**(실측). 빈 위젯만 나오므로 target·위젯·프롬프트 제거.
`project_usage_bar` 위젯 타입은 아래 IP/CAPACITY가 같은 모양을 쓰므로 **범용 `usage_bar`로 개명·일반화**한다
(rows의 `projectName` → `name`, `metric` → `title`). 무제한(value=null) 개념은 쿼터 전용이었므로 계약에서 제거.
d.ts에 남아있던 QuotaGaugeWidget 드리프트도 이번에 청소.

## 2. 신규 target 3종 (전부 실측 검증 완료)

| target | 질문 예 | PromQL (실측 결과) | 위젯 |
|---|---|---|---|
| `IP_USAGE` | "네트워크 IP 얼마나 썼어/남았어" | `(sum by(network_name)(openstack_neutron_network_ip_availabilities_used) / sum by(network_name)(…_total)) * 100` — 17건, 0.02~17.28% | `usage_bar` |
| `CAPACITY` | "스토리지 용량 얼마나 남았어" | `{__name__=~"ceph_cluster_total(_used)?_bytes\|openstack_cinder_pool_capacity_(total\|free)_gb"}` 한 방 → Ceph 2.75%·cinder 백엔드 2.94% | `usage_bar` (display에 절대량 "1.0 TB / 34.8 TB") |
| `AGENT` | "죽은 에이전트 있어?", "오픈스택 서비스 정상이야?" | `{__name__=~"openstack_(nova\|neutron\|cinder)_agent_state"} == 0` — 현재 0건/전체 44 | `threshold_banner` 재사용 (다운 없으면 GOOD 배너) |

조건 추출이 필요 없는 target들(STATUS/THRESHOLD와 동일한 data object 패턴).

## 3. TREND 확장 — 클러스터 시계열 2종

카탈로그에 `GAUGE_RAW` 패턴(조인·rate 없음, raw-metric을 그대로/표현식으로 사용) 추가:

| 카탈로그 키 | raw-metric | unit | 질문 예 |
|---|---|---|---|
| `TOTAL_VMS` | `openstack_nova_total_vms` | `대` | "VM 수 추이 보여줘" (실측: 1시리즈 61pt, 최근 50) |
| `STORAGE_USED` | `(ceph_cluster_total_used_bytes / ceph_cluster_total_bytes) * 100` (표현식 허용, GAUGE_RAW 한정) | `%` | "스토리지 사용량 추이" (실측: 1시리즈 61pt, 2.76%) |

`WidgetBuilder.metricLine` 시리즈 이름 폴백 확장: `instance_name ?: domain ?: name ?: nodename ?: "전체"`
(enrich 조인 경로는 조인이 이름을 보장하므로 안전 — 폴백은 GAUGE_RAW류에서만 발동).
METRIC(topk) 경로에서 이 두 키는 instance 라벨이 없어 빈 결과가 된다 — 프롬프트가 TREND로 유도하고,
개수 질문은 기존 INVENTORY가 담당.

## 4. 변경 요약

- 백엔드: `ResourceExtraction`에 IpUsage/Capacity/Agent Resolved 추가, ProjectUsage 제거.
  `DefaultResourceService` 분기 3개 + 답변 템플릿. `WidgetBuilder.usageBar(title, unit, rows, …)` +
  `agentDownBanner(...)`. `PromPattern.GAUGE_RAW`.
- 프롬프트/스키마: target enum 교체(6종: METRIC·TREND·INVENTORY·STATUS·THRESHOLD + 신규 3 − PROJECT_USAGE),
  few-shot 각 1개 + TREND 클러스터 예시 2개. ResourcePromptsWiringTest가 배선 가드.
- 프론트: `project-usage-bar.js` → `usage-bar.js`(필드 개명), dispatch·d.ts·preview 동기화.
- 검증: 단위(TDD) + 실서버 E2E(신규 target 3 + TREND 2 실값 확인).

## 5. 채택하지 않은 후보 (근거)

- node_*(62)·mysql·rabbitmq·haproxy: 인프라 내부 컴포넌트 — 챗봇 사용자(프로젝트/VM 관점) 질문 범위 밖.
- ceph_pool_percent_used(풀별 16건): 값이 0.0~0.18%로 전부 바닥이라 바 차트 정보가치 낮음. CAPACITY의
  클러스터 총량으로 갈음. 풀별 세분화는 필요해지면 CAPACITY 확장으로.
- openstack_glance_image_bytes 등 단건 카운트: INVENTORY(cb_common)와 중복.
