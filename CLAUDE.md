# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

# 프로젝트: ragbot-server (사내 LLM RAG 챗봇 봇 서버)

> 상세는 `docs/` 참고. 아래는 **절대 어기면 안 되는 불변식** 요약.

## 기술 베이스
- **Kotlin** · **JDK 21(LTS)** · **Spring Boot 3.5.14** · **Spring AI 1.1.7** · Gradle(Kotlin DSL)
- 데이터 접근 **`JdbcTemplate`**(JPA 금지). 회복탄력성 **Resilience4j 통합**(내장 재시도 비활성).
- 패키지: **도메인(능력)별 모듈 × Layered 4-tier**(`interfaces/application/domain/infrastructure`).

## 절대 불변식
1. **임베딩 = OpenAI API 호출**(`text-embedding-3-small`, 1536d). 로컬 모델 아님.
   **색인·질의 임베딩은 동일 모델·차원** — 최우선 불변식(어기면 검색 무의미).
2. 질문 임베딩은 **요청당 1회만** 계산해 문서 검색(+캐시 조회〔고도화〕)에 **재사용**(재계산 금지).
   1차는 검색에만 재사용하고, 캐시 삽입 seam은 유지한다.
3. 비싼 **LLM 생성은 검색 성공 경로에서만**(+캐시 미스〔고도화〕).
4. 토큰 낭비 방지: 검색 실패·**유해 입력(금칙어+Moderation)**(+캐시 히트〔고도화〕)을 호출 **전에** 차단.
5. 환각 방지: 검색 근거(top-k)에 기반해서만 답하고 **출처(title/page)** 표기.
6. 소유: **`documents`는 읽기 전용**(테이블 생성·색인 적재는 별도 — `db/init`), **시맨틱 캐시/질의
   로그 테이블은 이 앱이 소유**(DDL·조회/저장 — 구현은 고도화).
7. 시크릿은 **환경변수만**. 임계값·모델·top-k 등 튜닝값은 **`application.yml`**(하드코딩 금지).
8. **2차(고도화) 기능을 1차에 끌어오지 말 것**(범위 고정 — `docs/plan.md`).
   - **시맨틱 캐시(Phase 4)는 고도화로 보류**(삭제 아님) — 1차 파이프라인 = 임베딩→검색→생성.
     불변식 2~4·6의 *캐시* 항목은 고도화 시점에 적용하고, 1차에선 임베딩 재사용 seam만 유지한다.
     1차 비용 방어선은 검색 0건·유해 입력의 **호출 전 단락**(Phase 5)에서 확보한다.

## 프로젝트 문서
- [`docs/requirements.md`](docs/requirements.md) — 개요·목표·확정 시퀀스·데이터 소유·작업범위
- [`docs/architecture.md`](docs/architecture.md) — 기술스택·패키지 구조·컴포넌트 규약·설정
- [`docs/plan.md`](docs/plan.md) — Phase 0~7 개발 계획·DoD·튜닝표
- [`docs/process.md`](docs/process.md) — **단계별 개발·검수 사이클 규약**(Phase별 개발→검수, 큰 Phase 서브분할, Claude 검수 + 사용자 검수). Phase 실행 시 매번 따른다.
