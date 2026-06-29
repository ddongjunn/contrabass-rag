# RESOURCE 경로 — cb_common DB(INVENTORY) 트랙 합류 설계

> 상태: 설계(2026-06-29 개정) · 범위: 기존 **METRIC(Prometheus) 트랙**과 공존하는 **INVENTORY(cb_common DB) 트랙** 추가.
> 맥락: 팀 Phase 계획 [`../../phase2/plan.md`](../../phase2/plan.md)(METRIC 트랙 R0~R4) · 불변식 [`../../../CLAUDE.md`](../../../CLAUDE.md) · 규약 [`../../process.md`](../../process.md)
>
> **개정 이력**: 2026-06-22 초안은 ① Prometheus 트랙 부재를 가정, ② cb_common을 PostgreSQL로 오인했음.
> 본 개정은 둘을 바로잡고 **두 트랙 합류**를 설계한다.

## 1. 배경 — 지금 RESOURCE는 METRIC만 본다

`main`(7db4a88) 기준 RESOURCE 경로는 **Prometheus 지표 조회(METRIC)** 만 다룬다:

```
Router → RESOURCE → MetricQueryExtractor.extract(history)
                      └ ResourceExtraction.Resolved(ResourceQuery{metric, sort, topN, window, project})
                      └ ResourceExtraction.NeedsClarification(message)
   (R2~) → MetricCatalog → PromQlBuilder → PrometheusClient → ResourceAnswerTemplate
```

`MetricPattern`(INSTANCE_CPU/MEMORY/NETWORK_RX·TX/DISK_READ·WRITE)뿐이라 **"ACTIVE 아닌 인스턴스 목록",
"프로젝트 볼륨 개수" 같은 리소스 상태/목록 질문은 처리할 수 없다.** 이게 cb_common DB 트랙이 채울 빈칸이다.

## 2. 두 트랙의 경계 (METRIC vs INVENTORY)

| 구분 | METRIC (Prometheus, 기존) | INVENTORY (cb_common DB, 신규) |
|---|---|---|
| 질문 성격 | 수치·순위·시계열 | 존재·상태·목록·개수 |
| 예시 | "CPU 높은 VM top5", "메모리 사용률" | "ACTIVE 아닌 인스턴스", "prod 볼륨 목록/개수" |
| 데이터원 | Prometheus `/api/v1/query` | cb_common(EAV) SELECT |
| 답변 | top-N 지표값 | 리소스 행 목록/건수 |

경계 규칙: **"얼마나/사용률/높은·낮은 순"= METRIC**, **"무엇이 있나/상태가 뭐냐/몇 개/목록"= INVENTORY**.

## 3. 합류 지점 — RESOURCE 내부에서 1회 LLM로 분기 (비용 불변)

라우터(`DOC/RESOURCE/CLARIFY`)는 **그대로 둔다**(팀이 동결한 모듈, 프롬프트/테스트 안정성 유지).
대신 **resource 추출 LLM 1회 호출에 `target` 판별자를 추가**해 METRIC/INVENTORY를 가른다.
→ 리소스 경로 유료 호출 = 라우팅(#1) + 추출(#2)로 **현행과 동일**(추가 호출 없음, 불변식 3·4 유지).

> 대안(채택 안 함): 라우터를 `DOC/METRIC/INVENTORY/CLARIFY`로 확장. 분기를 중앙화하지만 routing 모듈·프롬프트·테스트를
> 건드려 동결 원칙과 충돌. **resource 내부 판별자**가 더 외과적이고 트랙 독립성↑.

### 추출 결과 타입 (sealed union 확장)

```kotlin
sealed class ResourceExtraction {
    data class MetricResolved(val query: MetricQuery) : ResourceExtraction()       // 기존 ResourceQuery → MetricQuery 개명
    data class InventoryResolved(val query: InventoryQuery) : ResourceExtraction() // 신규
    data class NeedsClarification(val message: String) : ResourceExtraction()      // 기존 유지
}
```

`ResourcePrompts.schema`에 최상위 `target: "METRIC"|"INVENTORY"|"CLARIFY"`와 INVENTORY 필드를 추가(strict 모드라
전 필드 required + additionalProperties:false 유지 — 해당 트랙이 아닌 필드는 LLM이 기본/누락값으로 채움).
추출기는 `target`으로 분기해 `MetricResolved`/`InventoryResolved`/`NeedsClarification` 생성.

## 4. INVENTORY 도메인 모델 (신규, cb_common)

```kotlin
enum class InventoryKind { INSTANCE, INSTANCE_SNAPSHOT, VOLUME, VOLUME_SNAPSHOT }

data class InventoryQuery(
    val kind: InventoryKind,
    val mode: Mode = Mode.LIST,                 // LIST | COUNT
    val filters: InventoryFilters = InventoryFilters(),
) { enum class Mode { LIST, COUNT } }

data class InventoryFilters(                     // 모두 nullable — null이면 해당 필터 무력화
    val status: String? = null,
    val statusOp: Op = Op.EQ,                    // EQ | NEQ
    val projectId: String? = null,
    val hypervisorHostName: String? = null,
    val instanceCreateEnable: Boolean? = null,   // 볼륨 스냅샷용
) { enum class Op { EQ, NEQ } }

data class InventoryRow(val uuid: String, val name: String?, val attrs: Map<String, String?>)
data class InventoryResult(val kind: InventoryKind, val rows: List<InventoryRow>, val total: Int, val appliedFilters: InventoryFilters)
```

## 5. cb_common 데이터 계약 (정정 — MySQL)

> ⚠️ **정정**: cb_common은 **MySQL**이다(documents/contrabass_rag의 PostgreSQL이 아님). (2026-06-29 확정)
> ① 드라이버 `com.mysql:mysql-connector-j`(`com.mysql.cj.jdbc.Driver`, url `jdbc:mysql://…/cb_common`),
> ② 예약어 `key` 컬럼은 **백틱**으로 인용(`` a.`key` ``), ③ PG 전용 문법(`::text`, `vector` 등) 사용 금지.

EAV 구조(타입/속성):

| 용도 | 테이블 | 주요 컬럼 |
|---|---|---|
| 리소스 본문 | `cm_resource` | id, provider_id, resource_type_id, uuid, resource_name, updated_at |
| 리소스 타입 | `cm_resource_type` | id, name |
| 리소스 속성 | `cm_resource_attr` | id, resource_id, `key`, value |
| 프로바이더 | `cm_provider` | id, uuid |

| InventoryKind | `cm_resource_type.name` | 주요 attr key |
|---|---|---|
| INSTANCE | `OS_VM` | status, tenant_id, power_state, hypervisor_host_name, availability_zone, image, flavor_* |
| INSTANCE_SNAPSHOT | `OS_VM_SNAPSHOT` | status, owner, visibility, created_at |
| VOLUME | `OS_VOLUME` | status, size, project_id, bootable, os_vol_host_attr_host |
| VOLUME_SNAPSHOT | `OS_VOLUME_SNAPSHOT` | status, size, project_id, volume_id, instance_create_enable |

> **불변 컨텍스트**: 모든 INVENTORY 조회는 `cm_provider.uuid`로 범위를 제한한다(멀티 provider 데이터 혼입 방지).

### 5.1 provider uuid 해석 (확정 2026-06-29)

조회 컨텍스트 uuid는 **`contrabass` DB(MySQL)의 `cb_provider` 테이블**에서 얻는다 — `url`이
`https://10.255.`로 시작하는 **연결 가능 공급자(단일)** 의 `uuid`. 이 값이 곧 `cb_common.cm_provider.uuid`다.

```sql
-- contrabass DB
SELECT uuid FROM cb_provider WHERE url LIKE 'https://10.255.%'   -- 단일 행 기대
```

v1 DataSource는 **2개**(env 주입 방식 채택 — contrabass 미연결):

| DataSource | 엔진 | 용도 |
|---|---|---|
| `documents`(기존, `@Primary`) | PostgreSQL+pgvector | RAG 문서 검색(읽기 전용) |
| `cbCommon` | MySQL | INVENTORY 리소스 EAV 조회(읽기 전용) |

**해석 방식 — ✅ env 직접 주입 채택 (2026-06-29)**
- `CB_PROVIDER_UUID` env로 주입. "단일·안정"이라 사실상 상수 → contrabass DataSource **불필요**(v1 단순).
  값은 위 SQL(`cb_provider WHERE url LIKE 'https://10.255.%'`)로 1회 확인해 운영 env에 넣는다.
- 따라서 v1 DataSource는 **`documents`(PG) + `cbCommon`(MySQL) 2개**. (contrabass는 연결하지 않음)
- INVENTORY SQL은 `cm_provider.uuid = ?`에 이 env 값을 바인딩한다.

## 6. EAV 피벗 SQL (MySQL/MariaDB, 파라미터 바인딩) — 인스턴스 예

```sql
SELECT r.uuid, r.resource_name,
       MAX(CASE WHEN a.`key` = 'status'               THEN a.value END) AS status,
       MAX(CASE WHEN a.`key` = 'power_state'          THEN a.value END) AS power_state,
       MAX(CASE WHEN a.`key` = 'hypervisor_host_name' THEN a.value END) AS hypervisor_host_name,
       MAX(CASE WHEN a.`key` = 'tenant_id'            THEN a.value END) AS tenant_id
FROM cm_resource r
JOIN cm_resource_type t ON t.id = r.resource_type_id AND t.name = 'OS_VM'
JOIN cm_provider p      ON p.id = r.provider_id      AND p.uuid = ?         -- 필수 컨텍스트
LEFT JOIN cm_resource_attr a
       ON a.resource_id = r.id
      AND a.`key` IN ('status','power_state','hypervisor_host_name','tenant_id')
GROUP BY r.id, r.uuid, r.resource_name
HAVING (? IS NULL OR MAX(CASE WHEN a.`key`='status' THEN a.value END) =  ?)   -- status EQ
   AND (? IS NULL OR MAX(CASE WHEN a.`key`='status' THEN a.value END) <> ?)   -- status NEQ
   AND (? IS NULL OR MAX(CASE WHEN a.`key`='hypervisor_host_name' THEN a.value END) = ?)
ORDER BY r.resource_name
LIMIT ?
```

- **안전 보장**: SELECT/JOIN/key 목록·resource_type 이름은 **코드 상수(화이트리스트)**. 사용자/LLM 값은 오직 `?` 바인딩
  → injection·환각 불가. LLM은 SQL을 만들지 않는다(불변식 5와 정합 — METRIC의 "PromQL 직접생성 금지"와 동일 철학).
- COUNT 모드 = 동일 조건 서브쿼리를 `SELECT count(*) FROM (…) sub`.
- `LIMIT` = `app.resource.inventory.max-rows` 상한.

## 7. 모듈 구조 변경 (기존 `resource/`에 추가)

```
resource/
├─ domain/
│  ├─ MetricPattern.kt            (기존)
│  ├─ MetricQuery.kt              ← 기존 ResourceQuery.kt 개명(METRIC 전용임을 명확히)
│  ├─ ResourceExtraction.kt       (기존, sealed union 3-way로 확장)
│  ├─ InventoryKind.kt            (신규)
│  ├─ InventoryQuery.kt           (신규)
│  ├─ InventoryFilters.kt         (신규)
│  └─ InventoryRow.kt / InventoryResult.kt (신규)
├─ application/
│  ├─ MetricQueryExtractor.kt     ← ResourceExtractor로 일반화(METRIC+INVENTORY 1콜 추출)
│  ├─ LlmMetricQueryExtractor.kt  ← target 분기 추가(개명 검토: LlmResourceExtractor)
│  ├─ ResourcePrompts.kt          (target 판별자 + INVENTORY few-shot/스키마 추가)
│  ├─ InventoryRepository.kt      (포트, 신규)
│  ├─ InventoryAnswerTemplate.kt  (순수함수, 신규 — 행/건수 → 답변+출처, LLM 무호출)
│  └─ ResourceService.kt          (오케스트레이터: 추출 → METRIC|INVENTORY 분기 → ChatResult)
└─ infrastructure/
   ├─ CbCommonDataSourceConfig.kt (신규: @Qualifier("cbCommon") DataSource + JdbcTemplate)
   └─ CbCommonInventoryRepository.kt (신규: §6 피벗 SQL, ? 바인딩, provider uuid)
```

- `documents` PostgreSQL DataSource는 **`@Primary`** 로 보존(자동구성 → 수동 빈 전환 시 PgVectorStore·기존 JdbcTemplate 무영향).
- `ResourceService`가 METRIC/INVENTORY 둘 다의 진입점. `DefaultChatService`(R4 배선)는 RESOURCE → `ResourceService.handle(history)` 하나만 호출.

## 8. 설정 추가 (`app.resource.inventory.*`) — 하드코딩/시크릿 금지

```kotlin
// AppProperties.Resource 에 추가
val inventory: Inventory = Inventory(),

data class Inventory(
    val enabled: Boolean = false,        // 접속정보 없으면 빈 미생성(@ConditionalOnProperty)
    val db: Db = Db(),                   // cb_common (MySQL)
    val providerUuid: String = "",       // ← env CB_PROVIDER_UUID (권장: 단일·상수). 비면 동적 해석(아래)
    val maxRows: Int = 50,
    val queryTimeoutMs: Int = 3000,
) {
    data class Db(
        val url: String = "",            // ← env DP_COMMON_URL (예: jdbc:mysql://10.255.72.176:30007/cb_common)
        val username: String = "",       // ← env DP_COMMON_USER_NAME
        val password: String = "",       // ← env DP_COMMON_PASSWORD (yml 평문 금지)
        val driverClassName: String = "com.mysql.cj.jdbc.Driver",
    )
}
```
```yaml
app:
  resource:
    inventory:
      enabled: ${RESOURCE_INVENTORY_ENABLED:false}
      db:                                # cb_common (MySQL)
        url: ${DP_COMMON_URL:}
        username: ${DP_COMMON_USER_NAME:}
        password: ${DP_COMMON_PASSWORD:}
      provider-uuid: ${CB_PROVIDER_UUID:}    # 권장: cb_provider 조회로 1회 확인해 주입
      max-rows: 50
      query-timeout-ms: 3000
```

> 의존성: `runtimeOnly("com.mysql:mysql-connector-j")` 추가(build.gradle.kts). 기존 pgvector(PostgreSQL)와 공존.
> (대안) 동적 해석 채택 시 `contrabass` DataSource용 `CONTRABASS_DB_URL/USER/PASSWORD` env + `ProviderContextResolver` 추가.

## 9. 단계 계획 (METRIC 트랙과 병행, phase2 R-시리즈 옆에 DB-시리즈)

> METRIC 트랙(R2~R4)은 팀이 진행. 아래 DB 트랙은 독립 빌드/검수 가능 단위. R4(배선)는 공유.

- **DB-1 INVENTORY 추출 합류** — `ResourceExtraction` 3-way 확장 + 프롬프트/스키마에 `target`·INVENTORY 필드 + 추출기 분기.
  스텁 테스트(키·DB 불필요): METRIC/INVENTORY/CLARIFY 분기, 필터 매핑, 저신뢰→CLARIFY. **외부 I/O 0.**
- **DB-2 cb_common DataSource + INSTANCE 조회** — 2차 DataSource(MariaDB) + `CbCommonInventoryRepository.findInstances`.
  SQL 파라미터 매핑 순수 단위테스트. 실연결은 사용자 검수(env).
- **DB-3 볼륨/스냅샷 + 답변 템플릿 + ResourceService 분기** — 나머지 3종 + `InventoryAnswerTemplate` + 오케스트레이션.
- **R4 공유 배선** — `DefaultChatService` RESOURCE → `ResourceService.handle`(METRIC·INVENTORY 자동 분기). DOC 회귀 보존.

## 10. 열린 질문

**확정됨 (2026-06-29)**
- ✅ **DB 엔진/드라이버** = **MySQL** (`com.mysql:mysql-connector-j`, `com.mysql.cj.jdbc.Driver`).
- ✅ **provider uuid 출처** = `contrabass.cb_provider`의 `url LIKE 'https://10.255.%'` 단일 공급자 uuid (= `cm_provider.uuid`). §5.1.
- ✅ **uuid 공급 방식** = **env 직접 주입**(`CB_PROVIDER_UUID`). contrabass 미연결, v1 DataSource 2개.

**잔여 (DB-2 실연결 전 확인)**
1. **네트워크 도달성/계정**: 봇 배포 환경에서 `cb_common`(10.255.72.176:30007)에 닿는가? **읽기 전용 계정** 확보 가능?
2. **데이터 신선도**: cb_common EAV가 실시간인가(동기화 배치면 답변에 "기준 시각" 표기 필요).
3. **METRIC↔INVENTORY 모호 질문**("네트워크 상태 어때?")의 기본 처리 — CLARIFY로 되물을지, METRIC 우선인지.
4. 멀티 리소스 조인("볼륨+연결 인스턴스")은 v1에서 단일 리소스로 분해 허용?

> 잔여 1·2는 **DB-2(실 DB 연결)** 전에 확인하면 된다. DB-1(추출 합류)은 외부 I/O가 없어 지금 착수 가능.

## 11. 불변식 점검 (CLAUDE.md)

- `documents` 읽기 전용·임베딩 모델/차원 — 영향 없음. cb_common도 **읽기 전용 SELECT**만.
- 비싼 호출 필요 경로 한정 — 리소스 경로 LLM = 라우팅+추출 2회로 **현행 유지**(INVENTORY 추가로 늘지 않음). DB 1회.
- 설정 외부화·시크릿 환경변수 — §8 준수(MySQL 접속정보·provider uuid 전부 env).
- 단일 진입점 = `ChatService` → `ResourceService` → (METRIC|INVENTORY).
- 환각 방지 — LLM은 SQL/PromQL 미생성, 화이트리스트 템플릿만(METRIC·INVENTORY 동일 철학).
