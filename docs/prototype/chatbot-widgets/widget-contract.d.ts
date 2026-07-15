// 챗봇 위젯 — 프론트↔백엔드 데이터 계약 (POST /api/chat 응답)
// 이 파일은 "규격"이다. 백엔드 Kotlin `Widget.kt` / `ChatResponse.kt`와 1:1 대응.
// 런타임은 순수 JS지만, 타입은 이 .d.ts를 기준으로 이해한다. 예시 페이로드는 ./mock-responses.json.
//
// 규칙:
//  - `answer`(평문)는 항상 존재. 위젯은 시각적 보강일 뿐(없으면 answer 텍스트 버블로 폴백).
//  - `severity`/`display`는 백엔드가 계산해 넣는다. 프론트는 재계산·재포맷 금지.
//  - 위젯 값(instanceName 등)은 반드시 textContent/DOM으로 삽입(XSS 금지, innerHTML 조립 금지).

/** 백엔드가 계산하는 색상 레벨. %지표만 채워지고, B/s·req/s 등은 null(색상=액센트). */
export type Severity = "GOOD" | "WARN" | "CRIT";

/** status_donut 세그먼트 색상 레벨(도넛 전용, 소문자). */
export type DonutLevel = "good" | "warn" | "crit" | "muted";

export type WidgetType =
  | "metric_rank"
  | "inventory_count"
  | "threshold_banner"
  | "quota_gauge"
  | "status_donut"
  | "project_usage_bar";
// Phase 2: "resource_dashboard"

/** POST /api/chat 응답 전체. */
export interface ChatResponse {
  answer: string;            // 평문(항상 존재) — 캡션 & Slack/스크린리더 폴백
  sources: string[];         // 출처 문자열(DOC 경로에서 채워짐)
  widgets: Widget[];         // 없으면 [] → 텍스트 버블로 폴백
  followups: string[];       // 연관질문 칩(최대 3). 없으면 []. 클릭 시 해당 문자열을 재질문
}

export type Widget =
  | MetricRankWidget
  | InventoryCountWidget
  | ThresholdBannerWidget
  | QuotaGaugeWidget
  | StatusDonutWidget
  | ProjectUsageBarWidget;

/** TopN 랭킹 표(막대). Prometheus topk 결과. */
export interface MetricRankWidget {
  type: "metric_rank";
  title: string;             // "CPU 사용률이 높은 인스턴스"
  unit: string;              // "%", "B/s", "req/s" (원단위)
  window: string;            // "5m"
  promql: string;            // 근거 표기(환각 방지 취지)
  empty: boolean;            // true면 결과 0건 → 빈 상태 카드
  rows: MetricRankRow[];
}
export interface MetricRankRow {
  instanceName: string;
  projectName: string | null;
  value: number;             // 바 길이 계산용 원단위 값
  display: string;           // 포맷 완료 문자열 "91.2%", "410 MB/s"
  severity: Severity | null; // %지표만; 아니면 null(색상=액센트)
  spark?: number[];          // 1b: range 시계열(옵션). 없으면 스파크라인 미표시
}

/** 개수 카드. cb_common COUNT. */
export interface InventoryCountWidget {
  type: "inventory_count";
  label: string;             // "볼륨" | "인스턴스" | "스냅샷"
  total: number;
  condition: string | null;  // "상태=available · 프로젝트 전체"
}

/** 임계 초과 경고 배너. */
export interface ThresholdBannerWidget {
  type: "threshold_banner";
  level: Severity;           // 보통 CRIT/WARN
  title: string;             // "임계 초과 노드 2대"
  detail: string | null;     // "CPU 85%↑ : web-prod-07, api-prod-02"
  count: number;
}

/** 쿼터 게이지(복수). openstack-exporter 쿼터. */
export interface QuotaGaugeWidget {
  type: "quota_gauge";
  items: QuotaItem[];
  empty: boolean;            // true면 결과 0건 → 빈 상태 카드
}
export interface QuotaItem {
  resource: string;          // "vCPU" | "메모리" | "디스크"
  used: number;
  quota: number | null;      // null = 무제한(-1)
  ratio: number | null;      // 0~1, null = 무제한
  display: string;           // "820 / 1000" | "8 / 무제한"
  severity: Severity | null; // 무제한이면 null
}

/** 상태 분포 도넛. Prometheus count by(status). */
export interface StatusDonutWidget {
  type: "status_donut";
  label: string;             // "인스턴스"
  total: number;
  segments: StatusSegment[]; // count 내림차순(동수는 status 이름순) — 서버가 정렬해 보냄
  empty: boolean;            // true면 결과 0건 → 빈 상태 카드
}
export interface StatusSegment {
  status: string;            // "ACTIVE" | "SHUTOFF" | "ERROR" ...
  count: number;
  level: DonutLevel;
}

/** 프로젝트별 쿼터 사용률 바. (used/max*100, 무제한은 value=null) */
export interface ProjectUsageBarWidget {
  type: "project_usage_bar";
  metric: string;            // "vCPU" | "메모리" | "디스크"
  unit: string;              // "%"
  rows: ProjectUsageRow[];   // 사용률 내림차순, 서버가 상한(app.resource.widgets.project-usage-top-n)까지 잘라 보냄
  empty: boolean;            // true면 결과 0건 → 빈 상태 카드
}
export interface ProjectUsageRow {
  projectName: string;       // tenant 이름(라벨에 이미 존재)
  value: number | null;      // null = 무제한
  display: string;           // "82%" | "무제한"
  severity: Severity | null;
}
