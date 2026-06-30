# RESOURCE INVENTORY(cb_common DB) 트랙 실행 계획

> 설계: [`../specs/2026-06-22-resource-db-query-design.md`](../specs/2026-06-22-resource-db-query-design.md) ·
> METRIC 트랙(병행): [`../../phase2/plan.md`](../../phase2/plan.md) · 규약: [`../../process.md`](../../process.md) · 불변식: [`../../../CLAUDE.md`](../../../CLAUDE.md)
> 작업 브랜치: `feat/resource-cb-common-inventory` (main 머지는 나중에). 단계: `DB-1 → DB-2 → DB-3`, R4 배선은 METRIC 트랙과 공유.

**Goal:** 기존 METRIC(Prometheus) 트랙과 **공존**하는 INVENTORY(cb_common DB) 트랙을 추가한다.
RESOURCE 추출 LLM 1회에 `target`(METRIC|INVENTORY) 판별자를 더해 분기하고, INVENTORY는 cb_common(MySQL, EAV)을
화이트리스트 템플릿 + 파라미터 바인딩으로 안전하게 조회한다. **자유 SQL/환각 없음.**

**확정 사항(2026-06-29):** DB=MySQL(`mysql-connector-j`) · provider uuid=env `CB_PROVIDER_UUID` · v1 DataSource 2개(documents PG + cbCommon MySQL).

---

## Phase DB-1 — INVENTORY 추출 합류 (외부 I/O 0, 지금 착수)

> RESOURCE 추출을 METRIC/INVENTORY/CLARIFY **3-way**로 확장. DB·Prometheus 없이 추출 로직만. 스텁 테스트(키 불필요).
> **Surgical 원칙**: 기존 METRIC 동작·이름 보존. 기존 `ResourceQuery`(METRIC) 개명하지 않고 INVENTORY 타입을 **추가**한다.

**건드릴 파일**
- 신규: `resource/domain/InventoryKind.kt`, `InventoryQuery.kt`, `InventoryFilters.kt`
- 수정: `resource/domain/ResourceExtraction.kt`(union 확장), `resource/application/ResourcePrompts.kt`(target+INVENTORY),
  `resource/application/LlmMetricQueryExtractor.kt`(target 분기 파싱)
- 테스트: `src/test/.../resource/LlmMetricQueryExtractorTest.kt`에 INVENTORY 케이스 추가(기존 METRIC 케이스 보존)

- [ ] **Step 1: INVENTORY 도메인 추가**
  - `InventoryKind { INSTANCE, INSTANCE_SNAPSHOT, VOLUME, VOLUME_SNAPSHOT }`
  - `InventoryFilters(status, statusOp(EQ|NEQ), projectId, hypervisorHostName, instanceCreateEnable)` — 모두 nullable, statusOp 기본 EQ
  - `InventoryQuery(kind, mode(LIST|COUNT), filters)`

- [ ] **Step 2: `ResourceExtraction` 3-way 확장**
```kotlin
sealed class ResourceExtraction {
    data class Resolved(val query: ResourceQuery) : ResourceExtraction()          // METRIC (기존 유지)
    data class InventoryResolved(val query: InventoryQuery) : ResourceExtraction() // 신규
    data class NeedsClarification(val message: String) : ResourceExtraction()      // 기존
}
```

- [ ] **Step 3: `ResourcePrompts`에 target 판별자 + INVENTORY 추가**
  - SYSTEM: METRIC vs INVENTORY 경계 규칙(수치/순위=METRIC, 목록/상태/개수=INVENTORY) + INVENTORY few-shot 3~4개
    (예: "ACTIVE 아닌 인스턴스"→INVENTORY/INSTANCE/NEQ ACTIVE, "prod 볼륨 개수"→INVENTORY/VOLUME/COUNT/project=prod).
  - 기존 METRIC few-shot에 `"target":"METRIC"` 추가.
  - `schema()`: `target` enum[METRIC,INVENTORY] + INVENTORY 필드(kind/mode/status/statusOp/hypervisorHostName/instanceCreateEnable) 추가.
    strict 모드 유지(전 필드 required + additionalProperties:false; 해당 트랙 아닌 필드는 기본/누락값).

- [ ] **Step 4: 추출기 `toExtraction` target 분기**
  - `RawExtraction`에 필드 추가(target, kind, mode, statusOp, hypervisorHostName, instanceCreateEnable; projectId는 기존 project 재사용).
  - 분기: clarify/저신뢰 → NeedsClarification → `target=="INVENTORY"`면 `InventoryKind.valueOf`(실패→clarify)로 `InventoryResolved`,
    아니면 기존 METRIC 경로(`Resolved`). 로그에 target 추가.

- [ ] **Step 5: 스텁 테스트 추가(기존 METRIC 케이스 보존)**
  - INVENTORY 매핑: "ACTIVE 아닌 인스턴스 목록"·"prod 볼륨 개수"·status EQ/NEQ·mode LIST/COUNT.
  - 알 수 없는 kind → NeedsClarification. 저신뢰 → NeedsClarification.
  - `JAVA_HOME=…temurin-21… ./gradlew test --tests "*resource*"` 그린.

- [ ] **Step 6: 커밋·푸시**
  - `git commit -m "feat(resource): INVENTORY 추출 합류 — target 판별자 + cb_common 의도 모델(DB-1)"` → `git push -u origin HEAD`

**DoD:** 키·DB 없이 METRIC/INVENTORY/CLARIFY 분기·필터 매핑 스텁 테스트 그린. 기존 METRIC 테스트 회귀 없음.

---

## Phase DB-2 — cb_common DataSource + INSTANCE 조회 (외부 I/O: MySQL)

> 실 cb_common 접속 + 인스턴스(OS_VM) EAV 피벗 조회. **DB-2 전 잔여 확인**(spec §10): 네트워크 도달성·읽기전용 계정.

**건드릴 파일**
- `build.gradle.kts`: `runtimeOnly("com.mysql:mysql-connector-j")`
- 신규: `resource/infrastructure/CbCommonDataSourceConfig.kt`(@Qualifier("cbCommon") DataSource+JdbcTemplate),
  `resource/domain/InventoryRow.kt`/`InventoryResult.kt`,
  `resource/application/InventoryRepository.kt`(포트),
  `resource/infrastructure/CbCommonInventoryRepository.kt`(OS_VM 피벗 SQL)
- 수정: `AppProperties.Resource`(+Inventory), `application.yml`(app.resource.inventory.*), documents DataSource `@Primary` 보장

- [x] **Step 1: MySQL 드라이버 + 2차 DataSource**
  - `CbCommonDataSourceConfig`: `app.resource.inventory.db.*`로 DataSource, queryTimeout 적용 JdbcTemplate.
    `@ConditionalOnProperty(app.resource.inventory.enabled=true)`로 미설정 시 빈 미생성(로컬/CI 부팅 보존).
  - 활성화 시 documents(PG) DataSource·JdbcTemplate을 `@Primary`로 명시(자동구성 백오프 대비) → pgvector·문서검색 무영향.

- [x] **Step 2: INSTANCE 조회(SQL 빌더 순수 단위)**
  - `InventoryRow/InventoryResult`, `InventoryRepository.findInstances(filters, mode, providerUuid, limit)`.
  - `CbCommonInventoryRepository`: spec §6 OS_VM 피벗 SQL(MySQL 백틱), `cm_provider.uuid=?` + 필터 `?` 바인딩, LIMIT=max-rows, COUNT 모드.
  - SQL/파라미터 매핑은 `InventorySql`(순수 object)로 분리해 **단위테스트**(EQ/NEQ/host/project/COUNT, null 무력화) 그린. 실 DB는 DB-3 통합(env-gated).

- [x] **Step 3: 커밋·푸시** `feat(resource): cb_common MySQL DataSource + 인스턴스 EAV 조회(DB-2)`

**DoD:** SQL 빌더 단위테스트 그린. (사용자 실연결) "ACTIVE 아닌 인스턴스"·"host-01 개수"가 cb_common 실데이터로 동작.

---

## Phase DB-3 — 볼륨/스냅샷 + 답변 템플릿 + ResourceService 분기

- [ ] **Step 1:** `CbCommonInventoryRepository`에 OS_VOLUME/OS_VM_SNAPSHOT/OS_VOLUME_SNAPSHOT 조회 추가(각 화이트리스트 key·필터).
- [ ] **Step 2:** `InventoryAnswerTemplate`(순수함수): 행/건수 → 한국어 답변 + 출처(대상·적용 필터·건수). LLM 무호출.
- [ ] **Step 3:** `ResourceService`(오케스트레이터): 추출 → `when` { Resolved(METRIC)→Prometheus 트랙 / InventoryResolved→repository+template / NeedsClarification→되물음 } → `ChatResult`.
- [ ] **Step 4:** env-gated 통합 스모크(`DP_COMMON_URL`+`OPENAI_API_KEY`) + README INVENTORY 섹션.
- [ ] **Step 5: 커밋·푸시** `feat(resource): 볼륨·스냅샷 조회 + 답변 템플릿 + ResourceService 분기(DB-3)`

**DoD:** INVENTORY 4종 대표 질문이 cb_common 근거로 답변+출처. METRIC 트랙 무영향. `./gradlew test` 그린.

---

## R4 — 파이프라인 배선 (METRIC 트랙과 공유, phase2 §R4)

> `DefaultChatService` RESOURCE → `ResourceService.handle(history)` 하나만 호출(METRIC·INVENTORY 자동 분기).
> `ChatCommand.history` 추가·Slack 스레드 히스토리는 phase2 R4-a/b/c와 동일. DOC 경로 회귀 보존.

---

## 진행 메모 (브랜치 작업 로그)
- 2026-06-29: 브랜치 생성, 설계 확정(MySQL·env uuid), DB-1 착수.
