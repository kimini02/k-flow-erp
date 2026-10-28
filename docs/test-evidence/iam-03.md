# IAM-03 UserRole 구현 및 검증 Evidence

- 상태: **LOCAL VERIFIED / 원격 최종 HEAD 검증 대기 / PM3 리뷰 전**. main 병합하지 않음.
- 기준 main: `5c813f0e7ef823c207a976c1efca7a2977c3f68c`; 착수·제출 준비 시 GitHub 재조회 일치.
- 작업 브랜치: `codex/iam-03-user-role`.
- 기존 baseline 457 PASS는 IAM-03 결과로 재사용하지 않았다.
- 로컬 실제 `./mvnw -B -ntp verify`: **Surefire 69 + Failsafe 504 = 573 PASS**, Failures/Errors/Skipped 각각0, BUILD SUCCESS.
- 원문: [성공 실행과 개별 소스 SHA-256](iam-03-run.txt), [실제 개발 실행 기록](iam-03-development-run.txt). 로컬 사용자명·개인 경로만 치환했으며 실패/예외 출력은 제거하지 않았다.
- 실제 로컬 실행 버전: Java21.0.2, Spring Boot4.1.1, Spring7.0.9, Hibernate7.4.5.Final, Testcontainers2.0.5, PostgreSQL16.15, PostgreSQL JDBC42.7.13, Flyway12.4.0. 성공 로그와 별도 dependency:tree 출력으로 확인했다.
- 테스트는 PostgreSQL16 Testcontainers의 격리된 synthetic fixture다. 기존 ORG/HR/IAM 테스트를 실제 함께 실행했으며 운영 데이터·운영 배포 검증이 아니다.

## 승인 설계와 구현 대응

코드 작성 전에 직접 읽은 기준 문서:

- [IAM-03 UserRole 역할 부여·철회 상세설계 v0.1 — R2 ADOPTED](https://drive.google.com/file/d/1ewt_bm_Mt6xU2Nrx0mGBcIeHfxW-8XiZ/view): I3-D01~22 및 BQ-G01~07의 최우선 업무·Transaction·Lock 계약.
- [IAM-02 Role 기준정보 상세설계 v0.1 — R2 ADOPTED](https://drive.google.com/file/d/1dtugv3kDEsYqIb6-IRocvYV6eyszenq1/view): I2-D07/D08/D17과 기존 Role 명령 의미, 전체 미철회 집합 철회 및 배포 통합 조건.
- [Organization HR Core IAM Core Authentication Authorization Implementation Plan v0.1](https://drive.google.com/file/d/1GfbER7GIRq7pL6Wz0G9oYsPFh9OT5Q8F/view): IAM-03 R2 채택 동기화 및 AN/AZ 등 후속 경계. 문서의 과거 NOT_STARTED 기록을 현재 구현의 실행 결과로 해석하지 않는다.

아래는 소스의 책임 대응표다. 실행 PASS 판정은 별도 실제 테스트·로그·CI 결과에서 확인한다. 경로는 Repository root 기준이며 `iam/`·`hr/` 축약은 `backend/src/main/java/com/kflow/erp/` 아래다.

| Decision / 상태 | 실제 코드와 책임 |
| --- | --- |
| I3-D01 ADOPTED — 소유권·정체성 | `iam/domain/UserRole.java`: grantId별 독립 Entity, company/user/role UUID 참조. Employee 중복 ID, ManyToMany, 부모 컬렉션, Role snapshot 없음. |
| I3-D02 ADOPTED — 신규 부여 적격성 | `iam/application/UserRoleService.grantRole`: Company→User 연결 사실→HR Guard→ACTIVE Role→최신 User 순서. ENABLED/DISABLED 모두 허용, User/Role/Employee version·generation 변경 없음. |
| I3-D03 ADOPTED — HR 목적별 Guard | `hr/api/EmployeeRoleGrantGuard.requireGrantableEmployee`; `hr/application/EmployeeRoleGrantService`: MANDATORY 참여와 Root rollback-only. `EmployeeLinkFacts.requireGrantableEmployee`가 locking read 뒤 재직·기간·회사 검증. 기존 AccountLinkGuard를 호출하지 않으며 HR 내부 scalar row reader만 재사용. |
| I3-D04 ADOPTED — grantId·retry | `GrantRole`, `UserRoleValues.grantId`, `UserRole` 생성자: 호출자 UUID v4/variant 검증·보존. `JpaUserRoleStore.add`는 `persist`만 사용하고 ID 자동 교체/merge/upsert/성공 receipt 없음. |
| I3-D05 ADOPTED — 미철회 pair 최대 1개 | `V9__iam_user_role_grants.sql`: `uq_iam_user_role_unrevoked` partial UNIQUE index. `JpaUserRoleStore.translate`는 지정 이름+23505만 USER_ROLE_ALREADY_GRANTED로 변환. 사전 exists만으로 유일성을 보장하지 않음. |
| I3-D06 ADOPTED — 상태·재부여 | `UserRole.facts`, `UserRoleView`: revokedAt 유무로 UNREVOKED/REVOKED 파생. 새 부여는 새 ID INSERT, 기존 행 복원·삭제·재사용 명령 없음. |
| I3-D07 ADOPTED — Company와 FK | V9: 부모 `uq_iam_user_company_id`, `uq_iam_role_company_id` 생성 후 `(company_id,user_id)` / `(company_id,role_id)` composite FK. 두 FK의 UPDATE/DELETE RESTRICT·NOT DEFERRABLE. HR/Organization schema 수정 없음. |
| I3-D08 ADOPTED — 사유·기록 시각 | `IamValues.reason`의 ASCII SPACE trim, 1~500 Unicode codepoint·공백만·control·고립 surrogate 거부를 재사용. `UserRoleService` 및 통합 `RoleService`가 Root Instant를 한 번 캡처. `UserRole.revoke`가 triple 한 번 저장. V9에 revokedAt≥grantedAt 제약 없음. |
| I3-D09 ADOPTED — version·개별 철회 | `UserRole` boxed `@Version`은 transient null→persist 0, 실제 dirty UPDATE에서 ORM 증가. `UserRoleService.revokeUserRole`: Role SHARE→grant UPDATE→최신 expectedVersion→정합성→fresh no-op/실제 MANUAL. no-op는 저장 triple·version을 변경하지 않음. |
| I3-D10 ADOPTED — standalone Root·commit | `UserRoleService` 및 기존 `RoleService`: ambient 거부 후 writable READ COMMITTED TransactionTemplate, 실제 commit 바깥에서 결과 반환·예외 변환. `JpaUserRoleStore.requireWritableTransaction`은 같은 EMF의 참여 EM과 실제 Connection autoCommit/readOnly/isolation 및 PostgreSQL 설정 확인. |
| I3-D11 ADOPTED — 잠금 순서 | grant: `HrAccountLinkStore.lockForLink`의 Employee FOR SHARE→`lockRoleShare`→`lockUserShare`→INSERT. revoke: `findRoute`→Role SHARE→`lockUserRole`. deactivate: Role UPDATE→DB UUID id ASC grant UPDATE. KEY SHARE 대체 없음. |
| I3-D12 ADOPTED — 최신 집합·국소 최신화 | `RoleService.change`가 Role lock/version 검사 뒤 별도 `unrevokedIds` statement 실행. `JpaUserRoleStore.lockUserRole`은 명시 FOR UPDATE 뒤 해당 Entity만 refresh. scalar reads는 현재 transaction-bound JDBC connection 사용, query auto-flush/전체 clear/detach 없음. |
| I3-D13 ADOPTED — Entity UPDATE | `UserRole.revoke` 및 `RoleService.change`: 개별 Entity dirty checking, 대상별 flush는 같은 Root 안에서만 수행. JPQL bulk·수동 version 증가·FORCE_INCREMENT·중간 commit 없음. |
| I3-D14 ADOPTED — deactivate 통합 | 기존 `RoleService.deactivateRole` 하나가 `UserRoleStore.unrevokedIds`의 정확한 S 전체를 ROLE_DEACTIVATION으로 변경한 뒤 Role 상태를 변경. User/Employee 상태 join/filter·public revoke 명령 반복·NoOp adapter·Role-only 우회 없음. |
| I3-D15 ADOPTED — INACTIVE 손상 | `RoleService.change`: stale 비교 뒤 INACTIVE+S≠∅이면 ROLE_GRANT_INTEGRITY_ERROR. activate/deactivate 모두 자동 수리 거부. `revokeUserRole`도 INACTIVE+미철회 대상이면 거부하며 정상 철회 이력의 fresh no-op만 허용. |
| I3-D16 ADOPTED — 정밀 오류 | `JpaUserRoleStore.translate`: 23505+지정 partial index/PK만 각각 USER_ROLE_ALREADY_GRANTED/GRANT_ID_CONFLICT. 해당 ORM optimistic 실패는 VERSION_CONFLICT. 나머지 FK/CHECK/UNIQUE/timeout/deadlock/cancel/connection 원인은 보존. |
| I3-D17 ADOPTED — retry·결과 불명확 | `UserRoleService`·통합 `RoleService`: 자동 retry/새 ID/보상 삭제 없음. actual commit 실패는 callback DTO 성공으로 반환하지 않음. Connection/TransactionSystemException을 업무 중복으로 바꾸지 않으며 재조회는 원 요청 receipt가 아님. |
| I3-D18 ADOPTED — 내부 Query·Port | `UserRoleQueryService`: 단건·User별/Role별 이력, UNREVOKED/REVOKED/전체, 20 기본·1~100·size+1·hasNext·grantedAt DESC/id ASC. 각 DTO는 한 scalar SQL row에서 생성. 신규 cross-module Port는 HR Guard 하나뿐. |
| I3-D19 ADOPTED — Actor/Audit 경계 | UserRole 컬럼·Command에 actor/Session/Permission/Audit/대량 실행 receipt 없음. 부여·철회 사유/시각/kind를 전체 감사나 실행자 검증으로 표현하지 않음. |
| I3-D20 ADOPTED — additive Migration·배포 | V9에 부모 UNIQUE→자식 테이블/제약→partial UNIQUE 및 이력 index 두 개. V1~V8 수정 없음. 신·구 artifact 호환 탐지나 자동 rolling/복구 Framework 없음. 아래 배포 gate 적용. |
| I3-D21 ADOPTED — 운영 공개 후속 | IAM 내부 저장·조회만 추가. Controller·HTTP API·Credential·로그인·Session·Authorization·ADMIN seed 없음. 저장 성공과 접근 허가를 분리. |
| I3-D22 ADOPTED — QA·Evidence | `UserRoleContractTest`, `UserRoleIT`, `UserRoleConcurrencyIT`, `UserRoleHrIT`, `UserRoleMigrationIT`와 기존 Foundation/모듈 경계/ORG/HR/IAM 회귀 연결. I3-T01~38 및 R1/R2 필수 하위 일정의 실제 결과는 별도 QA 대응표와 실행 로그 기준. |

### BQ-G01~07 반영

| 승인 정책 | 구현 선택 |
| --- | --- |
| BQ-G01 ADOPTED — EMPLOYED 신규 grant | HR 목적별 Guard가 Employee SHARE 아래 현재 재직 사실을 확인. TERMINATED는 EMPLOYEE_TERMINATED. 기존 grant는 퇴사만으로 자동 철회되지 않음. |
| BQ-G02 ADOPTED — DISABLED 사전 grant | User FOR SHARE를 유지하며 ENABLED/DISABLED 모두 허용. User 상태·generation은 변경하지 않음. 로그인 허용 의미 없음. |
| BQ-G03 ADOPTED — ON_LEAVE 지원 보류 | 새 HR 목적별 오류 ON_LEAVE_ROLE_GRANT_UNSUPPORTED. AccountLinkGuard의 기존 오류/계약은 보존. |
| BQ-G04 ADOPTED — 동일 의도 동일 ID | 호출자 grantId UUID v4, 신규 persist, 전 이력 PK 보존. 다른 ID는 새 의도이며 자동 동일성 추정 없음. |
| BQ-G05 ADOPTED — 중복 충돌·stale 우선 | 지정 partial UNIQUE/PK 충돌 구분; revoke는 최신 version 우선 검사 뒤 정상 이미 철회 행의 no-op. |
| BQ-G06 ADOPTED — 최소 근거 | grantedAt/reason 불변 및 revokedAt/reason/kind triple·version만 보존. 전체 Audit/Actor는 후속. |
| BQ-G07 ADOPTED — 손상 중단 | INACTIVE+UNREVOKED는 진단 오류; 자동 activate/철회/backfill로 감추지 않음. 배포 전 발견 시 중단·별도 복구 승인 필요. |

### 선택과 비용

- 부모 composite UNIQUE 두 개는 기존 UUID PK와 index 비용이 중복되지만 Company 결합 FK를 DB에서 확인하기 위해 승인된 비용을 수용했다.
- User SHARE는 ENABLED/DISABLED 모두 허용하는 경우에도 기존 상태 변경과의 순서를 명확히 하는 승인 계약으로 유지했다. Employee/Role 뒤에만 획득한다.
- 전체 철회는 Entity·@Version·국소 refresh 방식이다. 대상별 flush로 일부 변경 뒤 실패도 같은 Root에서 rollback되며, 대상 수가 커지면 왕복·메모리·dirty-check 비용이 늘어난다. 별도 규모 측정값을 운영 최대치/SLA로 확대하지 않는다.
- `EmployeeLinkFacts` 내부의 작은 공통 행 검증과 기존 HR scalar row reader만 재사용했다. 두 Published Guard의 API와 ON_LEAVE 목적 오류는 별개다.
- ISO control·Unicode 공백·고립 surrogate 등 전체 reason 검증은 Application 책임이다. V9의 최소 CHECK가 전체 Unicode 업무 규칙까지 막는다고 주장하지 않는다.
- ID 순서는 Java UUID compareTo가 아니라 PostgreSQL `ORDER BY id ASC`를 사용한다. Role lock 이후 새로운 statement로 집합 S를 취득하므로 대기 중 선행 grant commit을 놓치지 않는다.

### 통합 배포 Gate와 한계

1. 구형 Role-only writer 중지 및 진행 중 Root 배수.
2. V1~V8 checksum 보존 상태에서 additive V9 실행.
3. UserRole writer와 원자적 deactivate가 함께 포함된 통합 artifact만 시작.
4. 정합성 진단과 REQUIRED QA 결과를 확인한 뒤 내부 사용 가능 여부를 판단.

구형 Role-only artifact와 신규 grant writer 동시 실행, UserRole schema DB에 구형 artifact 재배포, Role-only 우회 경로, NoOp 철회 adapter, 테이블 존재 여부에 따른 runtime 호환 분기를 허용하지 않는다. 이번 작업은 실제 운영 배포·복구 자동화가 아니다.

진단 예시(조회 전용, 자동 수리 아님):

```sql
SELECT g.id
FROM iam_user_role g
JOIN iam_role r ON r.company_id = g.company_id AND r.id = g.role_id
WHERE g.revoked_at IS NULL AND r.status = 'INACTIVE';
```

I3-T39~42 DEFERRED 및 T43 DOCUMENTED LIMITATION 유지. 실제 AN/AZ Session·요청 접근 반영, 마지막 관리자 보호·TOTP·Bootstrap·Actor·전체 Audit, 실제 네트워크 ACK 유실/commit-window 전수 시험, raw SQL/savepoint 우회 전체 방어, 페이지 간 snapshot·원 요청 receipt는 이번 저장 구현의 보장이 아니다. 기존 IAM-02 I2-T29~31의 원본 DEFERRED 분류를 바꾸지 않고 IAM-03의 실제 통합 Evidence를 별도로 연결한다.


## REQUIRED QA 대응표

각 메서드는 `backend/src/test/java/com/kflow/erp/iam/` 아래다. [Contract](../../backend/src/test/java/com/kflow/erp/iam/UserRoleContractTest.java), [기본/경계](../../backend/src/test/java/com/kflow/erp/iam/UserRoleIT.java), [Role 경쟁](../../backend/src/test/java/com/kflow/erp/iam/UserRoleConcurrencyIT.java), [실제 HR 경쟁](../../backend/src/test/java/com/kflow/erp/iam/UserRoleHrIT.java), [Migration](../../backend/src/test/java/com/kflow/erp/iam/UserRoleMigrationIT.java). QA ID와 parameterized 테스트 실행 수는 다른 개념이다. 원문의 분류와 심각도를 유지한다.

| QA | 분류 | 심각도 | 실제 상태 | 테스트·검사 대응 |
| --- | --- | --- | --- | --- |
| I3-T01 | REQUIRED | High | PASS | `UserRoleIT.t01_t08_t09_enabledOrDisabledGrantRealSqlDtoAndParentPreservation` |
| I3-T02 | REQUIRED | High | PASS | `UserRoleContractTest.t02_callerGrantIdRequiresRfcUuidV4WithoutRegeneration`; `UserRoleIT.t02_t03_t06_t23_immutableIdentityHistoryAndSpecificGrantRetry` |
| I3-T03 | REQUIRED | Critical | PASS | `UserRoleIT.t02_t03_t06_t23_immutableIdentityHistoryAndSpecificGrantRetry`; `UserRoleConcurrencyIT.t03_t04_t05_partialUniqueWaitsForActualInsertCommitOrRollback` |
| I3-T04 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t03_t04_t05_partialUniqueWaitsForActualInsertCommitOrRollback` |
| I3-T05 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t03_t04_t05_partialUniqueWaitsForActualInsertCommitOrRollback` |
| I3-T06 | REQUIRED | High | PASS | `UserRoleContractTest.t06_t08_t09_revocationPreservesGrantIdentityAndFreshNoopFacts`; `UserRoleIT.t02_t03_t06_t23_immutableIdentityHistoryAndSpecificGrantRetry` |
| I3-T07 | REQUIRED | High | PASS | `UserRoleContractTest.t07_reasonUnicodeCodepointsAndOnlyAsciiSpaceTrim`; `UserRoleIT.t07_unicodeBoundariesAreAcceptedAndAsciiOnlyTrim`; `UserRoleIT.t07_invalidReasonRejectedBeforeMutationIncludingNoop` |
| I3-T08 | REQUIRED | High | PASS | `UserRoleContractTest.t06_t08_t09_revocationPreservesGrantIdentityAndFreshNoopFacts`; `UserRoleIT.t01_t08_t09_enabledOrDisabledGrantRealSqlDtoAndParentPreservation` |
| I3-T09 | REQUIRED | High | PASS | `UserRoleContractTest.t06_t08_t09_revocationPreservesGrantIdentityAndFreshNoopFacts`; `UserRoleIT.t01_t08_t09_enabledOrDisabledGrantRealSqlDtoAndParentPreservation`; `UserRoleConcurrencyIT.t09_t10_twoFreshNoopsKeepAllStoredValues` |
| I3-T10 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t10_sameGrantRevokeWaitsThenUsesLatestVersion`; `UserRoleConcurrencyIT.t09_t10_twoFreshNoopsKeepAllStoredValues`; `UserRoleConcurrencyIT.t10_differentGrantCommitsWhileFirstGrantLockIsHeld` |
| I3-T11 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t11_twoActualGrantsShareTheSameRoleConcurrently` |
| I3-T12 | REQUIRED | High | PASS | `UserRoleConcurrencyIT.t12_twoRoleShareHoldersBlockBareNonKeyWriterButNotOtherRole` |
| I3-T13 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t13_t14_grantFirstThenDeactivateUsesExactPostLockSet` |
| I3-T14 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t13_t14_grantFirstThenDeactivateUsesExactPostLockSet` |
| I3-T15 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t15_t16_deactivateFirstGrantReadsFreshCommittedState` |
| I3-T16 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t15_t16_deactivateFirstGrantReadsFreshCommittedState` |
| I3-T17 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t17_exactWholeSetIncludesDisabledAndTerminatedAndPreservesHistoryAndNonTargets` |
| I3-T18 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t18_partialFlushConfirmedAbortRestoresWholeSetAndAllParents` |
| I3-T19 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t19_fullSetAndRoleFlushedThenConfirmedAbortRestoresEveryValue` |
| I3-T20 | REQUIRED | High | PASS | `UserRoleConcurrencyIT.t20_activationAndInactiveGrantBothOrdersCommitOrRollback`; `UserRoleConcurrencyIT.t20_activeActivationNoopStillLocksButNeverRestoresPastGrant`; `UserRoleConcurrencyIT.t20_zeroGrantRoleDeactivatesNormally` |
| I3-T21 | REQUIRED | High | PASS | `UserRoleIT.t21_inactiveLiveGrantIntegrityFailureNeverRepairs` |
| I3-T22 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t22_lockWithoutUpdateAllowsImmediateCommittedDuplicateDecision`; `UserRoleConcurrencyIT.t22_flushedRevokeForcesUniqueRecheckAfterCommitOrRollback` |
| I3-T23 | REQUIRED | High | PASS | `UserRoleIT.t02_t03_t06_t23_immutableIdentityHistoryAndSpecificGrantRetry` |
| I3-T24 | REQUIRED | Critical | PASS | `UserRoleConcurrencyIT.t24_revokeAndDeactivateBothOrdersCommitOrRollback` |
| I3-T25 | REQUIRED | Critical | PASS | `UserRoleContractTest.t25_t33_onlyTwoExactUniqueConstraintsAndOptimisticAreTranslated`; `UserRoleIT.t25_threeIndependentForeignKeyFailuresAndExactCatalog`; `UserRoleIT.t25_dbChecksAreTechnicalAndNamedConstraints` |
| I3-T26 | REQUIRED | High | PASS | `UserRoleIT.t26_staleManagedGrantLocallyRefreshedWithoutProbeInterference`; `UserRoleIT.t26_grantLocksUseFreshScalarFactsDespiteStaleMappedParents`; `UserRoleIT.t26_t34_singleScalarFreshReadRetainsStaleManagedObjects` |
| I3-T27 | REQUIRED | Critical | PASS | `UserRoleIT.t27_t28_actualCommitAckWrapperAndPreciseRetries` |
| I3-T28 | REQUIRED | High | PASS | `UserRoleIT.t27_t28_actualCommitAckWrapperAndPreciseRetries` |
| I3-T29 | REQUIRED | Critical | PASS | `UserRoleIT.t29_actualRootPhysicalChecks`; `UserRoleIT.t29_hrPurposeGuardChecksActualParticipatingRoot`; `UserRoleIT.t29_allAmbientPropagationKindsRejectPublicRootCommands`; `UserRoleIT.t29_ambientWrongManagerAndSameRootProof`; `UserRoleHrIT.t29_guardFailureCaughtStillRollsBackRootAndRetainsSameConnection` |
| I3-T30 | REQUIRED | Critical | PASS | `UserRoleContractTest.t30_hrGrantPurposeDoesNotInheritAccountLinkOnLeaveFailure`; `UserRoleHrIT.t30_guardEligibilityAndDistinctPurpose`; `UserRoleHrIT.t30_t31_midnightKeepsActualPostLockDateAndSameDayRules` |
| I3-T31 | REQUIRED | Critical | PASS | `UserRoleHrIT.t31_threeIndependentRootsSixRequiredSchedules`; `UserRoleHrIT.t31_grantAndRealTerminationBothDirections`; `UserRoleHrIT.t30_t31_midnightKeepsActualPostLockDateAndSameDayRules` |
| I3-T32 | REQUIRED | High | PASS | `UserRoleHrIT.t32_realTransferAndUserStateRaces`; `UserRoleHrIT.t32_employeeShareBlocksBareNonKeyVersionUpdate` |
| I3-T33 | REQUIRED | High | PASS | `UserRoleContractTest.t25_t33_onlyTwoExactUniqueConstraintsAndOptimisticAreTranslated`; `UserRoleHrIT.t33_actualRoleWaitFailuresReleaseEmployeeShare`; `UserRoleHrIT.t33_realDeadlockIsTechnicalWithOneVictimAndNoRetry`; `UserRoleHrIT.t33_actualCommitAbortAfterFlushPreservesAllRows` |
| I3-T34 | REQUIRED | Medium | PASS | `UserRoleContractTest.t34_t37_narrowPublicCommandsDerivedStateAndNoAuthorizationSurface`; `UserRoleIT.t26_t34_singleScalarFreshReadRetainsStaleManagedObjects`; `UserRoleIT.t34_t35_historyFiltersPaginationTieBreakersAndErrors` |
| I3-T35 | REQUIRED | Medium | PASS | `UserRoleIT.t34_t35_historyFiltersPaginationTieBreakersAndErrors` |
| I3-T36 | REQUIRED | Critical | PASS | `UserRoleMigrationIT.t36_v8UpgradePreservesEveryOriginalRowAndChecksumAdditiveParentKeysAndEmptyGrants` |
| I3-T37 | REQUIRED | Critical | PASS | `UserRoleContractTest.t34_t37_narrowPublicCommandsDerivedStateAndNoAuthorizationSurface`; `ModuleArchitectureTest`, `LayerArchitectureTest`, `FrameworkProblemHandlerTest`, 아래 범위·배포 경로 점검 |
| I3-T38 | REQUIRED | High | 계측·회귀 PASS / 운영 최대 규모 OPEN | `UserRoleHrIT.t38_measuredBoundedGrantSetSuccessAndRollback`; 전체 verify 및 원격 CI 절 |
| I3-T39 | DEFERRED | Critical | 미구현·미검증 유지 | Authentication/Authorization/Session 실제 접근 판정 후속 |
| I3-T40 | DEFERRED | Critical | 미구현·미검증 유지 | 관리자 확인·마지막 관리자 보호 후속 |
| I3-T41 | DEFERRED | Critical | 미구현·미검증 유지 | 실제 실행자·전체 대상 Audit 후속 |
| I3-T42 | DEFERRED | High | 미구현·미검증 유지 | 휴직·복직·재입사·정정 통합 후속 |
| I3-T43 | DOCUMENTED LIMITATION | Medium | 한계 유지 | 페이지 snapshot·commit 시각 순서·원 요청 receipt·새 ID retry 안전성·전체 Audit 미보장 |

### 핵심 SQL·경쟁 Evidence 읽기

성공 실행 로그의 `I3` 표식을 검색하면 실제 PID/XID, pg_blocking_pids, SQL 및 합성 행 ID를 확인할 수 있다.

- T04/05: 실제 INSERT가 선행 UNIQUE transaction을 대기. commit이면 `23505 + uq_iam_user_role_unrevoked`, rollback이면 후행 ID만 commit.
- T11/12: 같은 Role에 서로 다른 두 실제 grant의 SHARE가 공존. 사전 UPDATE lock 없는 비키 writer가 첫 holder 종료 후에도 나머지 holder를 대기한다. PostgreSQL이 현재 기다리는 MultiXact member만 표시할 수 있어 두 blocker가 동시에 배열에 나와야 한다고 가정하지 않는다.
- T17/18/19: 지원 명령으로 준비한 g1/g2/g3(부여 후 disabled/terminated 포함), 기철회 h1/h2, 다른 Role Q. 정확한 S 및 DB UUID ASC 잠금 순서 대조. 첫 grant만 flush한 실패와 모든 grant+Role flush 이후 실패를 독립 fixture/실제 SQL로 구분하며 새 transaction의 전체 행으로 rollback 확인.
- T20 R1-01: activate 선행 commit/rollback, INACTIVE grant SHARE 선행 후 activate commit/rollback 4개 일정. 별도 ACTIVE activate no-op 2개 및 대상0 deactivation. 과거 grant 자동 복원 없음.
- T25 FR-G01: 없는 User만/없는 Role만/잘못된 Company 3개 fixture. 두 FK의 실제 테이블·conkey/confkey 순서, 부모 UNIQUE, update/delete RESTRICT, NOT DEFERRABLE/validated 및 partial index predicate/columns를 catalog로 확인. Company 불일치의 먼저 발생한 FK 이름은 강제하지 않음.
- T26: 이미 로드된 Role/User/UserRole과 다른 dirty managed probe를 두고 실제 후행 잠금/scalar query 최신화. 전체 clear/detach·잠금 단계의 강제 flush 없음. 명시 `FOR UPDATE` 후 Hibernate refresh의 `for no key update`가 추가될 수 있으나 이미 확보한 UPDATE lock이 약화되는 것은 아니다.
- T29: IAM 및 새 HR Guard의 실제 RC/off/JPA 참여 확인; null Spring metadata는 actual connection으로 판단. REQUIRED/REQUIRES_NEW ambient public 명령은 거부. 실제 JPA NESTED는 callback 진입 전에 미지원 예외로 차단되며 savepoint 설정을 바꾸지 않았다. Guard 예외를 소비자가 catch해도 Root rollback-only.
- T31 R1-02: G/D/H의 6개 독립 일정. 1/2는 D→G 및 H→G, 3/4는 H→G→D를 실제 blocker로 기록. 5는 G commit+D 전체 flush abort+H commit, 6은 G commit+D commit+H 전체 flush abort. S0 또는 S0∪{g}, 전체 Role/Employee/tail 행과 grant triple/version 및 비대상을 대조. 한 Root abort가 다른 Root commit을 취소한다고 가정하지 않음.
- T30/T31 R1-03: Root별 테스트 Clock으로 10/11 23:59:59→10/12 00:00:01을 관찰. grant의 잠금 후 실제 EmployeeLinkFacts 날짜 판정값, HR termination의 효력일 및 당일 입사/전보 제한이 최초 10/11을 유지. OS/DB 시계를 바꾸지 않음.
- T32: 실제 HR-03 transfer 및 User enable/disable 양방향 commit/rollback 12개, 별도 Employee 비키 UPDATE 판별 시험. 단순 mock 결과로 대체하지 않음.
- T33: 실제 `55P03`, `57014`, `40P01`, 연결 종료 및 deferred constraint의 실제 commit abort. 연결 종료는 최초 locking statement의 PostgreSQL 오류와 닫힌 connection에서 rollback 실패가 발생하는 completion 예외를 따로 기록한다. UNKNOWN_OUTCOME은 실제 commit 뒤 test-only ACK wrapper로 확인했고 실제 네트워크 장애 전수 증거가 아니다.

R2 선택적 T31 HR 퇴사 선행 추가 4개 일정은 수행하지 않았다. T33 선택적 Role 대기 실패→Employee SHARE 해제→HR 진행은 timeout/cancel/connection으로 보강했다. 필수 T31 6개 일정과 별도다.

### 기존 IAM-02 및 회귀 보존

- I2-T29 → I3-T20/T15: 비활성 거부·재활성·새 ID grant 및 이전 이력 불변.
- I2-T30 → I3-T11~16: SHARE 공존·비키 UPDATE 차단·양방향 grant/deactivate commit/rollback.
- I2-T31 → I3-T17~19/T24: 전체 집합, 부분/전체 flush rollback, 개별 철회와의 포함/제외.
- 원본 IAM-02 문서/QA ID/DEFERRED 분류는 수정하지 않았다. 위는 이번 IAM-03에서 확보한 통합 증거다.
- Foundation의 정확한 table/entity 집합에 `iam_user_role`/`IamUserRole` 하나만 추가하고 migration 수9를 검증. 기존 OpenAPI 외부업무 경로 없음 검증 유지.
- RoleIT의 단계 한정 `UserRole 테이블 없음`은 `통합 테이블이 있어도 Role lifecycle만으로 grant 0건`으로 바꿨다. 기존 부모·다른 Role 전체 행 보존을 유지하고, `iam_user` 문자열 검사가 `iam_user_role`을 오인하지 않도록 실제 부모 SQL 식별자를 좁혔다.
- RoleMigrationIT는 기존 V7→V8의 정확한 제약/index 검증을 target8로 보존한 뒤 최신 context가 V9를 검증한다. 기타 migration의 최신 버전 기대값만 V9에 맞췄다.
- V1~V8 8개 파일은 기준 main과 바이트 동일하며 개별 SHA-256이 성공 로그 끝에 있다. Production HR 변경은 새 목적별 Guard/오류와 작은 내부 값 검증 재사용뿐이며 HR 전보·퇴사·generation writer를 변경하지 않았다.

## 규모 계측 및 운영 한도

아래는 최종 로컬 verify의 실제 단일 실행값이다. 두 크기는 측정 fixture일 뿐 승인된 운영 최대치가 아니다.

```text
I3 T38 measured N=16 success_ms=41.032417 lock_acquired_to_return_upper_ms=36.402542 heap_used_delta_bytes=1048576 full_flush_rollback_ms=28.916459 PostgreSQL=16.15 (Debian 16.15-1.pgdg13+2) Java=21.0.2 heap_max=2147483648 processors=8 SQL logging enabled. Heap delta is not per-request peak; no production maximum/SLA claim.
I3 T38 measured N=128 success_ms=260.303833 lock_acquired_to_return_upper_ms=256.31175 heap_used_delta_bytes=4194304 full_flush_rollback_ms=425.342333 PostgreSQL=16.15 (Debian 16.15-1.pgdg13+2) Java=21.0.2 heap_max=2147483648 processors=8 SQL logging enabled. Heap delta is not per-request peak; no production maximum/SLA claim.
```

- lock 값은 Role lock 획득 직후부터 command 반환까지의 상한 근사이며 실제 commit 후 반환까지 포함한다.
- heap_used_delta는 GC가 개입하는 JVM 전후 차이로 요청별 peak/allocation 보장이 아니다. SQL logging·Mockito spy가 켜진 테스트 환경이다.
- rollback 비용은 전체 flush 후 확정 abort, transaction 종료까지의 실제 소요 시간이다. 운영 최대 grant 수, p95/p99, 동시 부하, 배포 승인 SLA는 미확정이다. 임의로 이 수치가 운영상 충분하다고 결정하지 않았다.
- 기존 IAM의 유한 timeout 설정을 재사용했다. 실제 운영 규모에서 수용 불가하면 별도 설계 검토가 필요하며, bulk나 중간 commit을 선행 추가하지 않았다.

## 실제 개발 실패와 수정

1. **실제 Production 버그:** assigned UUID Entity의 boxed @Version을 생성자에서0으로 초기화해 Hibernate7.4.5가 detached로 판단하고 persist를 거부했다. transient null→ORM persist0으로 수정. API 저장 결과 version0과 이후 dirty UPDATE+1은 실제 DB/DTO로 검증. merge/upsert로 회피하지 않았다.
2. **테스트 기대값:** JPA NESTED가 명령 진입 전에 차단되는 실제 경로를 IamFailure로 기대했던 한 건을 NestedTransactionNotSupportedException+callback 미진입·데이터 불변으로 정정. Production 설정은 그대로다.
3. **테스트 관찰 방식:** PostgreSQL MultiXact 두 holder가 동시에 blocker 배열에 보인다고 가정했던 T12 두 건을 실제 현재 blocker→해제→다음 blocker 확인으로 정정. 두 SHARE holder 모두 종료 전 writer가 진행하지 못한다는 요구는 유지.
4. **테스트 진단 방식:** 서버 connection 종료 후 Spring/Hikari rollback 오류가 최초 query 오류를 가리는 경우를 한 예외 cause 탐색만으로 판단했던 한 건을 최초 statement와 completion 예외로 분리. 실제 연결 종료·전체 Root 실패·HR 후행 진행 검증은 유지.
5. 첫 집중 실행은 worker 실패 원인을 좁히기 위해 명시 중단(exit143)했다. 이를 정상 실행 완료나 제품 VM crash로 집계하지 않는다. 이후 재현·수정·최종 전체 verify를 별도로 보존했다.

필요한 선택: 정확한 Entity/version 계약을 우선하여 대상별 flush를 같은 Root에 유지했다. 규모에 따라 비용이 증가하며 현재 측정 이상을 보장하지 않는다. 새 상태·generic retry/receipt/lock Framework는 추가하지 않았다.

## 제출·최종 원격 검증

원격 결과는 실행 후 이 절과 PR에 실제 링크로 기록한다. 로컬 PASS만으로 최종 원격 CI 완료를 주장하지 않는다. 최종 Evidence 커밋 자체의 CI는 이전 코드 커밋 CI와 구분하여 PR 기록에 남긴다.
