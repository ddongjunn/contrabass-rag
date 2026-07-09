# CLAUDE.md — 챗봇 집계 시각화(위젯) 개선안

> 이 기능(RESOURCE·INVENTORY 응답을 웹 위젯으로 시각화) 작업자용 진입점.
> 프로젝트 전체 규약은 루트 [`CLAUDE.md`](../../../CLAUDE.md) 참고.

## 한 줄 요약

지금 `POST /api/chat`은 **평문**만 준다. Prometheus TopN·인벤토리 같은 **집계 응답을
`chat-widget`(웹)에서 표·바·카드로** 보여주는 게 목표. Slack은 평문 그대로.

## 문서 지도

| 무엇 | 경로 |
|---|---|
| **설계 문서(마스터)** | [`../../superpowers/specs/2026-07-09-chatbot-aggregation-widgets-design.md`](../../superpowers/specs/2026-07-09-chatbot-aggregation-widgets-design.md) |
| **정적 프로토타입** | [`./index.html`](./index.html)(대화 데모), [`./gallery.html`](./gallery.html)(위젯 갤러리) |
| 프로토타입 보는 법·주의 | [`./README.md`](./README.md) |

프로토타입은 **목업 데이터**(연동 없음). 실제 도메인 형태(`MetricSample`, `InventoryResult`)에만 맞춤.

## 범위 (Phase)

- **Phase 1(이번)**: `metric_rank`(TopN 랭킹) + `inventory_count`(카운트) + empty/error 상태.
  기존 조회 결과 재표현만 → 라우팅/집계 변경 0.
- **Phase 2**: `resource_dashboard`(요약). 새 요약 라우트 + 크로스소스(Prometheus+cb_common) 집계 필요.
- **Backlog**: 확장 위젯 후보(임계배너·프로젝트별 바·상태 도넛·쿼터 게이지·스파크라인·표) — 반영 미결정.

## 먼저 얼릴 두 계약 (1일차)

동시 작업의 전제. 이거 정해지면 4명이 안 기다리고 병렬로 감.

1. **위젯 JSON 스키마** (백엔드↔프론트) = 설계 §13 데이터 타입이 **원본**.
   `Widget` sealed + `@JsonTypeInfo`(`type` 판별자), `metric_rank`/`inventory_count`.
2. **위젯 DOM/클래스 구조** (프론트 내부) = 렌더러가 뽑는 HTML·클래스명. 프로토타입 마크업이 참조.

## 역할 분담 (2 백엔드 / 2 프론트)

- **BE-A · 계약&도메인**(크리티컬): `Widget` 타입·직렬화, `WidgetBuilder`, `MetricValueFormatter`, severity config + 단위/직렬화 테스트
- **BE-B · 파이프라인&집계**(+릴리스): `ResourceService`/`DefaultChatService` 연결, empty/error, Slack 무영향 회귀, P2 요약 집계
- **FE-A · 렌더러&바인딩**: `chat-widget.js` 타입 디스패치·렌더러, XSS-safe DOM, 접근성 + 렌더러 테스트
- **FE-B · 디자인&비주얼**: `chat-widget.css` 위젯 스타일, "브랜드 색 + 아티팩트 느낌"(여백·라운드·부드러운 그림자·그라데이션 차트)

## 이 기능의 불변식 (루트 불변식 위에 추가)

1. **위젯은 LLM 0회.** 기존 조회 결과(`MetricSample`/`InventoryResult`)의 재표현일 뿐. (루트 불변식 3·4와 정합)
2. **`answer`(평문)는 항상 함께 반환.** 위젯은 시각적 보강. 평문이 진실 원본이자 Slack·스크린리더 폴백.
3. **하위호환**: `ChatResponse.widgets` 기본 빈 배열. Slack은 widgets 무시, 기존 클라이언트 무영향.
4. **포맷 단일화**: 평문(Slack)과 위젯(웹)이 `MetricValueFormatter` **공유** → 숫자 드리프트 금지.
5. **severity는 서버 소유**: 임계치는 `application.yml`(하드코딩 금지, 루트 불변식 7). 프론트는 서버가 준 값만.
6. **XSS 금지**: 위젯 값(인스턴스명 등 DB/label 출처)은 `textContent`/DOM으로만 삽입. `innerHTML` 문자열 조립 금지(프로토타입은 예외).
7. **연관질문 칩**: LLM 아님. 이미 추출한 `ResourceQuery`(metric/project/instance) + top 결과로 규칙 생성.

## 미결정 (다음 논의)

- [ ] 확장 위젯 후보 중 무엇을 반영할지 (설계 §8)
- [ ] 연관질문 칩을 Phase 1 / Phase 2 중 어디에 넣을지 (재료는 이미 있어 P1도 가능)
- [ ] Phase 2 `resource_dashboard`를 별도 스펙/계획으로 분리할지
