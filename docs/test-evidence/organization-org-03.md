# ORG-03 Status Transition / Assignment Guard — Test Evidence

- 상태: **LOCAL VERIFIED / REMOTE CI VERIFIED / PM3 코드 리뷰·최종 판정 대기**.
- 실행일: 2026-10-24 (Asia/Seoul).
- 구현 전 및 제출 준비 시 직접 조회한 GitHub `main`: `bdf6f48032b8ddf53f0366d085e360d3a0c66c01` (ORG-02 PR #2 merge). 이 기준선은 ORG-03 구현 커밋이 아니다.
- 작업 브랜치: `codex/org-03-status-assignment-guard`. 로컬 검증은 위 기준선 이후 미커밋 working tree에서 실행했다.
- [최종 실제 실행 로그·XML 집계·개별 파일 SHA-256·개발 중 실패 발췌](organization-org-03-run.txt). 로컬 경로/사용자명만 placeholder로 치환했다. aggregate manifest hash는 주장하지 않는다.

## 직접 읽은 승인 설계

2026-10-24 연결된 Google Drive 도구로 네 문서의 실제 본문을 읽었다. ORG-03 결정·QA 전체 및 선행/경계 계약을 확인했으며 링크만 받은 상태를 열람으로 취급하지 않았다.

| 문서 제목 | 확인한 승인 상태 / 적용 | 원문 |
| --- | --- | --- |
| K-Flow ERP ORG-03 Detailed Design v0.1 — ADOPTED (R2), Organization Status Transition + Assignment Guard | 2026-10-10 사용자 최종 승인, O3-D01~18 전체 ADOPTED. R1/R2 보완 및 T24 적용 | [ORG-03 R2](https://drive.google.com/file/d/1oB7KPNjMRLyjUmjBnMI_El35toJ1TzQ6/view) |
| K-Flow ERP ORG-02 Department + Position Detailed Design v0.1 — ADOPTED (R2) | O2-D01~14 ADOPTED. 기존 값/참조/rename/목록 계약 유지 | [ORG-02 R2](https://drive.google.com/file/d/1TYFffMwHuRFOkkYjPWSd1zsKzYxEDh0a/view) |
| K-Flow ERP Architecture Overview v0.1 (`architecture-az-adopted-sync.md`) | 소유권, Published Port, Modular Monolith 및 채택 동기화 확인. 모든 OPEN/DRAFT를 확정으로 해석하지 않음 | [Architecture](https://drive.google.com/file/d/15TZeY1hgLowgAuhoe9t0--Qy2xuZcwFf/view) |
| Organization HR Core IAM Core Authentication Authorization Implementation Plan v0.1 (`organization-hr-iam-implementation-plan-az-adopted-sync.md`) | ORG-02 → ORG-03 → HR/IAM 구현·검증 순서. 후속 설계 승인과 이번 구현 범위 구분 | [Implementation Plan](https://drive.google.com/file/d/1GfbER7GIRq7pL6Wz0G9oYsPFh9OT5Q8F/view) |

Architecture 링크의 사용자 입력에는 `0–Q`(en dash)가 있었으며, 기존 ORG-02 Evidence에서 확인한 실제 Drive ID의 `0--Q` 링크로 원문을 읽었다. 설계 본문의 과거 NOT_STARTED/NOT_RUN 및 오래된 GitHub SHA는 작성 당시 기록이다. 현재 main 코드·ORG-02 완료 및 이번 명시적 구현 승인을 별도로 대조했으며 원문 설계를 다시 작성하지 않았다.

## 승인 설계와 기준 코드 차이

| 기준 코드 | ORG-03 반영 |
| --- | --- |
| Company/Site/Department/Position, V1/V2, 일반 Reference 조회와 REQUIRED rename | Company와 기존 조회/rename 계약 보존. Site/Department/Position에 독립 Root 상태 명령 6개 추가 |
| ACTIVE/INACTIVE 저장은 있으나 상태 명령·배정 검증 없음 | 최소 `OrganizationAssignmentGuard` Published Port 추가. 신규 목적지 triple 전체 존재/Company/ACTIVE 검사 |
| 상태 변경 근거 필드 없음 | additive V3의 nullable 마지막 시각/사유 쌍과 CHECK. 과거 근거 backfill 없음 |
| 기존 JPA 낙관적 변경과 completion-time conflict 검사 | 상태는 FOR UPDATE 후 JPA dirty update/@Version. Guard는 같은 Hibernate Connection의 fresh FOR SHARE 조회 |
| Foundation의 Migration 개수 2 | 3으로 갱신. 정확한 4개 production Entity/테이블 및 외부 업무 API 없음 검사는 유지 |

## 구현 선택과 한계

- 상태 명령: `OrganizationStateService`가 ambient Transaction을 거부하고 writable READ COMMITTED Root를 완료한다. 유효 reason/expectedVersion 필수, stale 우선, 최신 no-op은 필드 불변, 실제 변경만 version +1과 근거를 함께 저장한다.
- Guard: MANDATORY 참여 TransactionTemplate을 사용한다. Spring active/readOnly/isolation, transaction-bound EntityManager의 실제 참여, 같은 Hibernate Connection의 autocommit/isolation/readOnly 및 PostgreSQL 현재 Transaction 설정을 확인한다. null Spring isolation은 실제 DB 값으로 판정한다.
- fresh 잠금: infrastructure 내부 JDBC `doWork/doReturningWork`로 정확히 `FOR SHARE`, `FOR UPDATE`를 실행한다. Entity cache에 기대거나 공유 persistence context를 clear/detach/flush하지 않는다. 반복 SQL은 작지만 DB 왕복이 늘어나는 Trade-off가 있다.
- Guard 잠금 순서는 Site → Department → Position. 기존 `lock_timeout`이 0일 때만 `kflow.organization.lock-timeout-ms`(기본 5000ms)를 적용하고 성공 후 원래 값으로 복구한다. 소비자가 설정한 유한 timeout은 유지한다. SQL 오류 후에는 실패 Root를 중단하며 복구 SQL/자동 retry/두 번째 rollback을 시도하지 않는다.
- 일반 조회는 INACTIVE도 반환한다. 상태 쓰기는 Published Port가 아니며 외부 Controller/OpenAPI를 추가하지 않았다. 기존 rename의 REQUIRED/상위 commit 소유권 한계는 바꾸지 않았다.
- DB는 같은 행의 마지막 근거 쌍/길이/ASCII SPACE 정규화를 보장한다. control 문자 거부와 요청 reason 필수성은 Application 책임이다. 전체 Audit History·HR 역참조·상태 사용 건수 판정은 없다.
- 실제 Employee/EmployeeAssignment, 효력일, 예약 발령, HR 전체 저장 경로, IAM/Frontend는 미구현·미검증이다. test-only 소비자/참조 테이블과 관리 probe를 HR 구현으로 취급하지 않는다.
- raw SQL·NESTED/savepoint 재사용·Guard 후 다른 Transaction에 저장하는 잘못된 소비자를 Guard 진입 한 번으로 모두 차단할 수 없다. 정상 소비자의 같은 물리 Transaction·잠금 수명·전체 rollback은 검증했으며 실제 HR 경로는 후속이다.

## 실제 로컬 실행

Java 21.0.2 / macOS arm64 / Docker 27.4.0 / Testcontainers 2.0.5 / `postgres:16` 실제 PostgreSQL 16.15. H2 대체·skip 없음.

`backend` 디렉터리에서:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp verify
```

최종 완료: **2026-10-24T21:57:47+09:00**, exit 0, BUILD SUCCESS.

| 구분 | 실행 / PASS | 실패 | 오류 | Skip |
| --- | ---: | ---: | ---: | ---: |
| Surefire | 44 | 0 | 0 | 0 |
| Failsafe | 101 | 0 | 0 | 0 |
| 합계 | **145** | **0** | **0** | **0** |

| 테스트 클래스 | 실행 수 |
| --- | ---: |
| OrganizationValuesTest | 34 |
| OrganizationContractTest | 3 |
| ModuleArchitectureTest / LayerArchitectureTest | 1 / 3 |
| FrameworkProblemHandlerTest | 3 |
| OrganizationIT (ORG-01) | 22 |
| DepartmentPositionIT (ORG-02) | 21 |
| OrganizationConstraintTranslationIT | 3 |
| OrganizationV2MigrationIT / OrganizationV3MigrationIT | 1 / 1 |
| OrganizationStateGuardIT | 51 |
| FoundationIT | 2 |

V1/V2 파일 diff 없음. V3 upgrade는 기존 ID/code/status/version/시각/Company 값 및 V1/V2 checksum을 전후 비교한다. 기존 V2 Migration 테스트에는 `target("2")`를 명시해 원래 V1→V2 검사·assertion을 그대로 유지했다. 이후 실제 Spring 시작에서 최신 V3와 Hibernate validate도 수행한다. ORG-02 중복 삽입 fixture는 신규 nullable 컬럼 때문에 명시적 컬럼 목록만 추가했다. 기존 보장을 삭제하거나 실패 테스트를 skip하지 않았다.

## O3-D01~18 대응표

| 결정 (모두 ADOPTED) | 실제 구현 / 검증 |
| --- | --- |
| D01 상태 범위·행위 | Site/Department/Position 6개 내부 명령, Company 전환 없음. T01/T23, ContractTest |
| D02 사용 중 비활성화 | test reference가 있어도 비활성화 가능, 기존 ID/FK 참조 보존. T03/T23. 실제 HR DEFERRED |
| D03 미래 배정 | 미래 효력일/자동 전보 없음. 현재 ACTIVE 검증만 제공; T04 DEFERRED |
| D04 신규/기존 구분 | Guard 전체 triple 검사, 일부 ID 유지 예외 없음. T02/T23 |
| D05 최소 Published Port | UUID 4개 입력 + void Guard, Entity/저장 서비스 미노출. ContractTest, Modulith/ArchUnit |
| D06 같은 물리 Transaction | MANDATORY + 실제 Connection 검증, PID/XID + 전체 rollback + managed probe. T05/06/14/17/20 |
| D07 상태 Root / commit | standalone RC, ambient 거부, TransactionTemplate 완료 밖 실패 처리. T11/13/16/17 |
| D08 잠금 강도 / fresh | JDBC FOR SHARE/FOR UPDATE, 실제 SQL·비키 UPDATE 대기·stale JPA 조회 시험. T19/20 |
| D09 잠금 순서 | Site→Department→Position 실제 동일 PID/XID SQL 순서, 단계별 대기. T02/19 |
| D10 version / no-op | expectedVersion 선검사, JPA @Version +1, no-op 필드 불변. T07~12 |
| D11 retry / 기술 실패 | stale retry 거부, fresh no-op, 기술 실패 전파·자동 retry 없음. T07/08/16 |
| D12 재활성화 | ID/code/Company 보존, A/B test 참조 모두 허용·재비활성화 후 보존. T01/23 |
| D13 최소 변경 근거 | 모든 요청 reason 검증, 실제 변경 때만 시각/사유 저장. T09/24 |
| D14 DB / Application 책임 | V3 pair CHECK, DB half-null/blank/501 실패 및 1/500 성공. T24, V3MigrationIT |
| D15 오류/rollback | 정확한 업무 오류와 technical 오류 구분, catch해도 Root rollback-only. T02/13/14/16/18 |
| D16 Command/Query/API | 기존 단건·목록 계약 유지, 공개 관리 API 없음. FoundationIT/ContractTest/기존 회귀 |
| D17 우회 방어 범위 | 현재 모듈 경계 검사 + raw FK 한계 관찰. T21/22, 실제 HR 전 경로 DEFERRED |
| D18 완료·후속 구분 | 아래 Provider REQUIRED 검증, HR/운영 SQL/ACK fixture 한계 명시, PM3 최종 판정 대기 |

## O3-T01~24 개별 대응표

`S` = [OrganizationStateGuardIT](../../backend/src/test/java/com/kflow/erp/organization/OrganizationStateGuardIT.java). 종류 인자를 받는 메서드는 Site/Department/Position 각각 실행한다. QA ID 24개는 JUnit 24건을 뜻하지 않는다. **T04는 DEFERRED**, 나머지 23개 ID의 REQUIRED Provider/상태/계약 부분을 검증했다. 혼합 분류의 실제 HR 부분은 PASS로 주장하지 않는다.

| QA | 분류 / 실제 테스트 메서드·근거 |
| --- | --- |
| T01 | REQUIRED — S.`t01_t03_t08_t23_stateIdentityRetryAndReactivationPreserveExistingReferences`: ACTIVE↔INACTIVE, ID/code/Company/createdAt, DTO/DB version, 시각/사유 |
| T02 | REQUIRED Provider / HR DEFERRED — S.`t02_caughtGuardFailureStillRollsBackWholeConsumer`, `t02_partialGuardWaitKeepsEarlierLocksAndFailureAbortsRoot`: catch 후 write도 rollback, Site SHARE 보유→Department 대기→뒤 대상 비활성 실패→Root 해제 |
| T03 | REQUIRED 상태 / HR DEFERRED — S.`t01_t03_t08_t23_stateIdentityRetryAndReactivationPreserveExistingReferences`: 기존 FK test reference 보존하며 비활성화 |
| T04 | **DEFERRED OHI-01/HR** — 미래 Assignment/효력일 구현·PASS 없음 |
| T05 | REQUIRED Provider / HR-T10 DEFERRED — S.`t05_t14_guardFirstBlocksDeactivationUntilConsumerCompletion`: Guard 선행 commit/rollback 각각, 실제 pg_stat_activity/pg_blocking_pids 대기 확인 |
| T06 | REQUIRED Provider / HR-T10 DEFERRED — S.`t06_t15_stateFirstMakesGuardReadCommittedStateAfterWait`: 상태 선행 commit이면 최신 INACTIVE 거부, rollback이면 ACTIVE로 저장 가능 |
| T07 | REQUIRED — S.`t07_t10_doubleClickAndConcurrentNoops`: 같은 version 동시 deactivate 1성공/1VERSION_CONFLICT |
| T08 | REQUIRED — S.`t01_t03_t08_t23_stateIdentityRetryAndReactivationPreserveExistingReferences`: activate commit 후 이전 version retry 거부, 최신 version no-op. 실제 HTTP 응답 유실 실험은 아님 |
| T09 | REQUIRED — S.`t09_t24_allRequestsValidateVersionAndReasonIncludingNoops`: 양 상태/양 명령, null/음수/stale version, 필드 불변 |
| T10 | REQUIRED — S.`t07_t10_doubleClickAndConcurrentNoops`: 동시 최신 no-op 둘 다 성공, version/모든 근거 불변 |
| T11 | REQUIRED — S.`t11_noopLockLivesThroughActualRootCompletion`: beforeCommit gate 중 rename UPDATE 차단, 완료 뒤 +1, stale no-op 거부 |
| T12 | REQUIRED — S.`t12_renameAndStateRaceNeverLoseUpdates`: 실제 상태 선행·rename 선행 두 경쟁 일정, stale 거부와 name/status 보존 |
| T13 | REQUIRED — S.`t13_flushSuccessIsNotCommitSuccessAndDeferredAbortRestoresAllFields`: beforeCommit 예외 및 실제 PG deferred trigger 23514, 새 조회에서 모든 필드 원복 |
| T14 | REQUIRED Provider / HR DEFERRED — S.`t14_t17_managedProbeAndPhysicalTransactionSurviveGuardCommitAndRollback` + T05: Guard 전에 조회만 한 관리 객체 유지, 후속 dirty 변경 commit/rollback, test reference 전체 원복·잠금 해제 |
| T15 | REQUIRED — S.`t06_t15_stateFirstMakesGuardReadCommittedStateAfterWait`: 잠금 대기 뒤 rollback된 상태/근거를 사실로 보지 않음 |
| T16 | REQUIRED — S.`t16_lockTimeoutAndTerminatedConnectionRemainTechnicalFailures`, `t16_realPostgresDeadlockAbortsOneWholeRootWithoutBusinessTranslation`, `t16_commitAcknowledgmentLossIsNotReportedAsRollbackOrRetried`: 55P03, 실제 connection 종료, 실제 40P01, 아래 ACK fixture 한계 |
| T17 | REQUIRED — S.`t17_actualTransactionIsolationAndReadOnlyAreCheckedEvenWithNullMetadata`, `t17_unjoinedEntityManagerAndMissingBoundResourceCannotAuthorize`, `t17_t22_stateCommandsRejectAmbientRootsIncludingAfterGuardAndAsyncGuardHasNoRoot`, T14: 실제 기본 RC+null 허용, 실제 RR/serializable/readOnly/autocommit 거부, 메타데이터 불일치, 미참여/리소스 부재, async 및 ambient 거부, 동일 PID/XID |
| T18 | REQUIRED — S.`t18_missingWrongKindAndCompanyRemainDistinct`, `t18_companyZeroAndMultipleAreConfigurationErrorsWithRollback` + 기존 조회 회귀: 종류/ID/Company/구성 오류 분리, Company fixture는 rollback 후 원복 |
| T19 | REQUIRED — S.`t19_t20_shareGuardsCoexistBlockUnprefixedStatusUpdateAndEmitOrderedSql`: Guard 공존, 같은 PID/XID의 실제 SQL 3개 순서, 상태 FOR UPDATE SQL |
| T20 | REQUIRED — T19 + S.`t20_freshGuardIgnoresPreloadedStaleEntityWithoutClearingConsumer`, `t20_guardDoesNotAutoFlushUnrelatedManagedChanges`: 사전 FOR UPDATE 없는 status UPDATE 차단, 캐시 ACTIVE 대신 DB INACTIVE, 관리 상태·flush mode 및 미flush 변경 보존 |
| T21 | REQUIRED 경계 / HR DEFERRED / DOCUMENTED LIMITATION — Modulith/ArchUnit/ContractTest + S.`t21_databaseFkAloneDoesNotEnforceActiveAssignmentPolicy`: 현재 Java 경계 검사, raw SQL로 INACTIVE 참조 삽입 가능함을 확인. 정상 HR 모든 경로 검사와 혼동하지 않음 |
| T22 | REQUIRED 계약 / DOCUMENTED LIMITATION — S.`t17_t22_stateCommandsRejectAmbientRootsIncludingAfterGuardAndAsyncGuardHasNoRoot`: Guard 후 같은 Root 상태 명령 거부. savepoint/NESTED 재사용 미지원, 우회 방지 완료 주장 없음 |
| T23 | REQUIRED Provider / HR DEFERRED — T01 + S.`t23_newTripleStillValidatesAnUnchangedInactiveDestination`: 새 triple에서 비활성 ID 하나 유지·다른 두 ID 교체해도 거부, INACTIVE 단건/rename 가능, 재활성화 후 A/B test reference 보존 |
| T24 | REQUIRED — S.`t09_t24_allRequestsValidateVersionAndReasonIncludingNoops`, `t24_databaseRejectsHalfNullBlankAndOversizedStateEvidence`, [OrganizationV3MigrationIT](../../backend/src/test/java/com/kflow/erp/organization/OrganizationV3MigrationIT.java), V2/ORG01/02/Foundation 회귀: codepoint/control/ASCII trim/no-op, DB 쌍 부정 사례, V1/V2 보존·V3 적용 |

### 실제 증거와 fixture의 구분

- PG 서버 로그에서 실제 `FOR SHARE` 세 문장을 같은 PID/XID 순서로 확인하고 `O3-SQL` 줄로 출력했다. `FOR UPDATE`도 서버 로그 assertion으로 확인한다. `pg_stat_activity`의 Lock wait + `pg_blocking_pids`로 실제 대기를 검증한다.
- 테스트의 Mockito spy는 production 잠금/저장 메서드를 실제 실행한 뒤 경쟁 일정·commit 직전만 제어한다. 정상 SQL을 가짜 응답으로 대체하지 않는다.
- test-only 관리 객체는 `org.kflow.testprobe.ManagedProbe`, 별도 `org03_probe` 스키마다. production Entity/테이블 추가가 아니고 Employee도 아니다. test consumer는 Organization API만 import한다.
- T13 deferred trigger 및 T16 connection 종료/deadlock은 격리된 실제 PostgreSQL에서 실행했다. 고의 오류 로그는 정상 negative-case 검증이며 JUnit 실패 수와 다르다.
- T16 ACK 유실은 **실제 DB commit 직후 test-only TransactionManager wrapper가 예외를 던지는 fixture**다. 네트워크를 끊은 실제 commit ACK 장애로 과장하지 않는다. DB에는 전환이 남았고 commit 1/rollback 0, 자동 retry 없음, 새 조회 및 stale retry 거부를 확인한다. 조회가 원래 요청의 receipt임을 증명하지 않는다.

## 개발 중 관찰한 실패 / 수정 구분

1. 테스트 컴파일: 서로 다른 Entity Class를 switch 표현식으로 바로 `EntityManager.find`에 넘겨 제네릭 추론 실패. `Class<?>` 지역 변수로 수정했다. 제품 업무 로직 결함이 아니다.
2. 첫 전체 verify: test probe를 public schema에 미리 생성해 Flyway가 non-empty/no-history로 거부했다. Surefire 44 PASS, Failsafe 96 실행 중 오류 46. 새 StateGuardIT의 context 초기화가 실패했으므로 46개 업무 실패로 해석하지 않는다. probe를 별도 test-only schema로 옮겼고 baselineOnMigrate/검사 skip으로 우회하지 않았다.
3. 집중 실행: ContractTest 3 PASS, StateGuardIT 46 중 실패 3. spy에서 주입한 `IllegalStateException("abort root")`가 repository exception translation을 거쳐 `InvalidDataAccessApiUsageException`으로 반환됨을 확인했다. 정확한 wrapper·root cause·message 기대값을 정정했고 rollback/필드 불변/후속 Guard 성공 assertion은 유지했다.
4. 이후 미참여/비간섭/부분 유지 triple 사례를 추가하고 전체 verify **145 PASS**. 테스트를 통과시키려고 제품 검증을 약화하거나 업무 정책을 변경한 수정은 없다.

이 기록은 실제 남은 로그에서 확인되는 개발 실패다. 별도의 제품 장애를 꾸며 기록하지 않았다. V1/V2·Frontend·HR/IAM·설정·의존성은 변경하지 않았고, 기존 `.idea/`는 제외한다.

## GitHub 제출 / 원격 CI

- 구현 커밋: [`3afc9ce6db4e99ab40bdf462439adcd5a50478a6`](https://github.com/kimini02/k-flow-erp/commit/3afc9ce6db4e99ab40bdf462439adcd5a50478a6).
- [브랜치](https://github.com/kimini02/k-flow-erp/tree/codex/org-03-status-assignment-guard), [Draft PR #3](https://github.com/kimini02/k-flow-erp/pull/3), base `main`. 소스/테스트 24개 + Evidence 2개, 총 26개 파일. 개인 `.idea/` 제외.
- 원격 Backend workflow의 실제 명령: `./mvnw -B -ntp verify`.
- [PR CI run 40096472136](https://github.com/kimini02/k-flow-erp/actions/runs/40096472136) / [verify job 120879669377](https://github.com/kimini02/k-flow-erp/actions/runs/40096472136/job/120879669377): **SUCCESS**, head `3afc9ce6db4e99ab40bdf462439adcd5a50478a6`.
- [push CI run 40096442983](https://github.com/kimini02/k-flow-erp/actions/runs/40096442983): 같은 구현 HEAD의 **SUCCESS**. 테스트 상세 수치는 직접 다운로드한 위 PR job 로그를 기준으로 한다.
- PR job 로그: 2026-10-24T13:04:05Z(UTC) `BUILD SUCCESS`, Surefire **44** + Failsafe **101** = **145 PASS**, failures/errors/skipped 모두 **0**. PostgreSQL **16.15**, Testcontainers **2.0.5**, 실제 SQL 및 PID/XID 관찰 확인.
- 실제 원격 로그의 테스트 결과·환경·잠금 근거 발췌를 `organization-org-03-run.txt` 끝에 추가했다. 원본 job 링크와 UTC timestamp를 유지한다.
- 이 절은 **구현 커밋 CI**의 근거다. 이 결과를 기록하는 후속 문서 커밋의 CI 또는 main 병합 후 CI로 바꾸어 주장하지 않는다. 문서 동기화 후 최종 HEAD CI는 제출 보고에서 별도로 확인한다.
- main에 병합하지 않았다. ORG-03 Provider 구현/검증 제출 완료이며 PM3 리뷰·최종 판정 대기다. HR/IAM/Item을 시작하지 않았다.
