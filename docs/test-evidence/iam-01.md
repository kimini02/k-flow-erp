# IAM-01 — 직원 연결 계정 및 AN-03 AM-01 User writer Test Evidence

- 작성: 2026-10-27 (Asia/Seoul).
- 상태: **LOCAL + REMOTE VERIFIED (아래 코드 커밋 CI)**. PM3 코드 리뷰·병합 판정 대기. main에 병합하지 않음.
- 기준 main: `2fa852fba1c01fd967b302b8ac7537cac51005bb`. 착수 및 구현 중 GitHub에서 재조회. 선행 [main CI](https://github.com/kimini02/k-flow-erp/actions/runs/40431363057) SUCCESS.
- 브랜치: `codex/iam-01-employee-account`.
- 범위: IAM 내부 계정 생성·관리 상태·조회, HR 소유 직원 연결 Guard, AM-01 User generation writer. 로그인·세션·권한 부여 또는 철회 완료가 아니다.

## 1. 직접 읽은 승인 설계

| 원문 제목 | 승인 상태·이번 적용 | 원문 |
| --- | --- | --- |
| IAM-01 직원 연결 계정 Detailed Design v0.1 — R2 ADOPTED | I1-D01~20 ADOPTED, R2 R-01/R-02, I1-T01~30 REQUIRED | [Drive](https://drive.google.com/file/d/1R4OyRBy6Cka1IrmVxeYq-fQTveclDK-4/view) |
| AN-03-v0.1-ADOPTED-20261014 | AM-01 User generation의 후속 확장 계약만 이번 구현에 적용 | [Drive](https://drive.google.com/file/d/1NjmZOpoepDQxOu4KYx6HHOGJOuAOay5t/view) |
| HR-03-04 Integrated Detailed Design v0.1 — ADOPTED (R2) | 기존 전보·퇴사/기간·version·Root 계약 보존 | [Drive](https://drive.google.com/file/d/1WjGEfYVIpxbkyf2o0qmhgvD0KZf_KVZ-/view) |

Drive fetch로 내용을 직접 읽었다. 원문의 구현 전 NOT_RUN 문구를 현재 테스트 결과로 간주하지 않는다. 기준 main에는 ORG/HR와 V1~V6, Employee generation이 있었지만 IAM User·직원 연결 Guard는 없었다. IAM-01 본문에는 없던 generation은 나중에 승인된 AN-03 AM-01에 따라 추가했다. GitHub의 과거 PROPOSED 문서를 구현 정책으로 대체하지 않았다.

## 2. 실제 실행 결과

- 최종 명령(저장소 root): `JAVA_HOME=<JDK21> backend/mvnw -B -ntp -f backend/pom.xml verify` — 동일 backend Maven verify 전체 lifecycle.
- 앞선 전체 검증은 `backend/`에서 `./mvnw -B -ntp verify`로 실행하여 393 PASS. 이후 REQUIRED QA 보완 3개 실행을 추가해 최종 소스를 재검증했다.
- 최종 종료: **2026-10-27 11:47:29 +09:00**, **BUILD SUCCESS**.
- Surefire **58 PASS**, Failsafe **338 PASS**, 합계 **396 PASS**, failures/errors/skipped 모두 **0**.
- 기존 319개 + 신규 77개(IamContractTest 5, IamUserIT 71, IamMigrationIT 1). JUnit XML을 직접 합산했다.
- 실제 환경: Java **21.0.2**, Spring Boot **4.1.1**, PostgreSQL **16.15**, Testcontainers **2.0.5**, Hibernate **7.4.5.Final**.

- [최종 실행 로그·JUnit XML 집계·개별 파일 SHA-256](iam-01-run.txt).
- [실제 개발 실행 기록](iam-01-development-run.txt). 개인 홈 경로/실행 사용자 정보만 `<LOCAL_HOME>`/`<LOCAL_USER>`로 치환했다. development-run의 반복 Context 실패는 명시적 원문 발췌·라인 범위로 보존했고 최종 실행은 전체 출력이다. 로그를 성공처럼 재작성하지 않았으며 aggregate manifest hash 주장은 하지 않는다.
- V1~V6 Git 원문 불변. V6→V7 시험에서 기존 ORG/HR 모든 행·컬럼 값·기존 제약·Flyway checksum을 비교하고 보존을 확인한다. User seed 0, generation 기본값 0, Flyway 반복 migrate 0건 및 validate, Hibernate ddl-auto=validate.
- FoundationIT는 정확한 7개 업무 테이블/Entity와 V1~V7, empty OpenAPI paths를 검증한다. 기존 ORG/HR 검증을 삭제하거나 skip하지 않았다.
- 기존 테스트 변경은 최신 Migration 기대값 7, V3→최신 추가 수 4, HR-04 전용 V5→V6 시험의 명시적 target(6)뿐이다. 해당 시험 뒤 애플리케이션은 최신 V7까지 적용하여 Hibernate validate한다.

## 3. 구현 및 Trade-off

- [UserService](../../backend/src/main/java/com/kflow/erp/iam/application/UserService.java): 내부 createUser/enableUser/disableUser. 입력 검증 후 ambient Transaction을 거부하고 동일 manager/EMF/DB의 standalone writable READ COMMITTED Root를 소유한다. 실제 물리 경계 확인→Company 사실 확인→create만 HR Guard→IAM 저장 순서다. Root 안에서 Clock을 한 번 읽고 Asia/Seoul referenceDate를 계산한다. DTO는 flush 뒤 값으로 구성하고 실제 commit 완료 뒤 반환한다.
- [User](../../backend/src/main/java/com/kflow/erp/iam/domain/User.java): UUID v4·DISABLED/v0/g0 생성, 불변 식별자·canonical loginKey·creationReason. 실제 전이는 dirty UPDATE 1회로 @Version +1, status/reason/timestamps와 generation 동시 저장. disable만 g+1, enable/no-op은 g 유지, overflow는 Math.incrementExact로 Root 전체 실패. HR Employee generation을 수정하지 않는다.
- [JpaIamStore](../../backend/src/main/java/com/kflow/erp/iam/infrastructure/JpaIamStore.java): 명시적 User FOR UPDATE 뒤 해당 User만 국소 refresh. scalar projection 조회는 관리 Entity의 오래된 getter와 섞지 않는다. `23505 + uq_iam_user_employee`/`uq_iam_user_login_key`만 업무 충돌로 변환하며 다른 SQL 오류는 보존한다. Root 바깥 예외 분류, 자동 retry·보상 삭제 없음.
- [EmployeeAccountLinkGuard](../../backend/src/main/java/com/kflow/erp/hr/api/EmployeeAccountLinkGuard.java): void requireLinkable 하나만 HR Published Port에 추가. HR 자체 [서비스](../../backend/src/main/java/com/kflow/erp/hr/application/EmployeeAccountLinkService.java)가 MANDATORY 참여·전체 Root rollback-only를 소유하고 기존 HR 물리 경계 검사 재사용. [HR Store](../../backend/src/main/java/com/kflow/erp/hr/infrastructure/JpaHrAccountLinkStore.java)가 같은 Hibernate Connection의 Employee `FOR SHARE` scalar로 최신 상태를 읽는다. clear/detach/강제 flush·별도 commit·KEY SHARE 대체 없음.
- 생성 시 Organization ACTIVE Guard를 부르지 않는다. 기존 조직이 INACTIVE인 재직자도 연결 가능하다. 기존 User enable/disable은 HR Guard·Employee lock을 추가하지 않으며, TERMINATED/ON_LEAVE의 관리 상태 변경도 허용한다. 이는 인증 허가가 아니다.
- [V7](../../backend/src/main/resources/db/migration/V7__iam_employee_linked_user.sql): 단일 iam_user, 전 상태 직원/loginKey UNIQUE, HR 기존 (company_id,id) 부모 UNIQUE를 참조하는 RESTRICT·NOT DEFERRABLE 복합 FK, canonical/status/version/generation/reason/nullable pair/finite timestamp CHECK. DB가 재직·불변 식별자·과거 전이를 보장한다고 주장하지 않는다.
- IAM별 Clock은 qualifier로 주입하고 defaultCandidate=false로 기존 unqualified Clock의 Spring Modulith 소비와 충돌하지 않게 했다. HR Clock 또는 HR 업무 코드를 재설계하지 않았다.
- 유한 Root30s/lock5s/statement30s. timeout 시험만 test-only800ms, deadlock 시험만 역순 locker를 사용한다. 운영 성능 튜닝/전역 deadlock 부재 보장이 아니다.
- 추가 Framework, Role/Credential/Session, Controller, 외부 계정 API, seed 관리자 없음. IAM에서 HR Entity/Repository/테이블을 직접 접근하는 production 코드 없음. 테스트의 직접 SQL은 제약·부정 fixture·잠금 판별에 한정한다.

## 4. I1-D01~20 구현 대응

| Decision | 구현 및 검증 |
| --- | --- |
| D01 | User company/employee 필수, 전 상태 직원 UNIQUE. Employee 역참조·unlink/delete/reassign 없음. T01/T02/T04 |
| D02 | User UUID v4·v0, @Version actual+1, HR version 불변. T01/T18 및 기존 HR 회귀 |
| D03 | IamValues ASCII SPACE trim→ASCII 문법→Locale.ROOT lower. 터키 Locale·Unicode 거부·DB CHECK T03 |
| D04 | canonical 전 상태 UNIQUE·불변. T02/T05/T24, 지원 수정/재사용 명령 없음 |
| D05 | 생성 DISABLED, 입력에 status 없음. 내부 enable/disable만 T01/T18 |
| D06 | 기존 User 상태와 HR 독립. T21A 4조합, T21B 양방향 commit/rollback, T29 경계 |
| D07 | 최초 reason 불변, 최근 reason/시각 actual 전이만; no-op 보존. T18/T20/T23 |
| D08 | standalone writable RC·실제 commit 뒤 응답. T14/T25/T26/T27 |
| D09 | HR void Guard·명시 FOR SHARE·최신 scalar·Seoul d. T01/T13/T14/T16/T17 |
| D10 | NOT_FOUND/company mismatch/TERMINATED/ON_LEAVE/future/integrity 분류. T06~08 |
| D11 | MANDATORY·실제 Connection/PG setting·null metadata·전체 rollback·비간섭. T14~16 |
| D12 | Company→Employee SHARE→User INSERT. 실제 HR 전보 기존 순서 유지, no HR lock on user state. T04/T17/T21/T27 |
| D13 | create vs actual HR termination 네 일정. T09~12, 생성 선행 둘 다 성공 가능 |
| D14 | fresh User lock 아래 stale 우선·actual v+1·no-op 불변. T18~22 |
| D15 | 지정 SQLSTATE+constraint만 변환. T04/T05/T24/T27 |
| D16 | 확정 abort 전체 원복·ACK wrapper·재요청 충돌·불명확 기술 실패, 자동 보상 없음. T25~27 |
| D17 | 내부 단건/직원별/목록, minimal DTO·scalar·고정 정렬. 신규 HR Published Guard 하나. T28/T29 |
| D18 | V7 소유 복합 FK, 기존 부모 UNIQUE/V1~V6 보존. T24/T30 |
| D19 | 내부 계정 관리까지만. T31~33 DEFERRED, 외부 API·접근 허가 미구현 |
| D20 | 아래 REQUIRED 대응과 로컬/원격 실제 실행 근거. 후속 기능 자동 확장 없음 |

## 5. I1-T01~34 QA 대응

시험 파일: [IamUserIT](../../backend/src/test/java/com/kflow/erp/iam/IamUserIT.java), [IamContractTest](../../backend/src/test/java/com/kflow/erp/iam/IamContractTest.java), [IamMigrationIT](../../backend/src/test/java/com/kflow/erp/iam/IamMigrationIT.java). 표의 tNN은 실제 메서드 이름의 접두/포함 문자열이다. QA ID 30개는 JUnit 실행 수와 다르다.

| QA | 결과·범위 | 실제 테스트 |
| --- | --- | --- |
| T01 | REQUIRED PASS | t01_t02_t16: 실제 HR 등록 뒤 생성 UUIDv4/v0/g0/DISABLED, DTO·독립 DB·동일 PID/XID |
| T02 | REQUIRED PASS | t01_t02_t16: enable/disable 뒤 불변 ID/company/직원/key/creationReason; 재등록 거부. Contract 공개 명령 검사 |
| T03 | REQUIRED PASS | Contract t03: ASCII 길이3/64·잘못된 길이·Turkish Locale·금지문자. IT t03_t24 canonical DB CHECK/타입 길이 별도 |
| T04 | REQUIRED PASS | t04_t05 sameEmployee=true: 두 실제 FOR SHARE Guard 함께 통과→INSERT UNIQUE 대기, 선행 commit 충돌/rollback 후 성공 |
| T05 | REQUIRED PASS | t04_t05 sameEmployee=false: 다른 직원+대소문자 canonical 같은 key, commit/rollback, 실제23505·지정 이름 |
| T06 | REQUIRED PASS | t06_t07_t08: 없는 직원·다른 Entity UUID·회사 불일치·퇴사 직원, 저장0 |
| T07 | REQUIRED PASS | t06_t07_t08 ON_LEAVE_ACCOUNT_LINK_UNSUPPORTED, Employee 불변. 휴직 구현을 추가한 것이 아님 |
| T08 | REQUIRED PASS | 유효 미래 입사 경계 거부; Contract t08의 손상 scalar fixture HR_DATA_INTEGRITY_ERROR. 실제 DB 제약을 끄지 않음 |
| T09 | REQUIRED PASS | t09_t12_t17 terminate=true/iamFirst=true/commit: 생성 flush·SHARE 보유→actual 퇴사 대기→둘 다 commit |
| T10 | REQUIRED PASS | terminate=true/iamFirst=false/commit: actual 퇴사 변경 미커밋→Guard 대기→최신 TERMINATED로 생성 거부 |
| T11 | REQUIRED PASS | 생성 선행 abort→User0→actual 퇴사 commit·HR version/generation/기간 확인 |
| T12 | REQUIRED PASS | 퇴사 선행 abort→HR 전체 baseline 복원→Guard 통과·User1 |
| T13 | REQUIRED PASS | t13: 관리 Employee v0 유지 중 실제 별도 퇴사 commit, locking scalar는 TERMINATED 거부, Entity attached/stale 유지 |
| T14 | REQUIRED PASS | t14 경계 7종 및 wrong-manager/ambient, 실제 same PID+XID, metadata null+RC/off 허용·실제 RR/readOnly 거부. 자정 lock wait에도 Root Clock1회 |
| T15 | REQUIRED PASS | t15: 소비자가 Guard 실패 catch 후 test-only INSERT+flush해도 UnexpectedRollback·User0 |
| T16 | REQUIRED PASS | 실제 Guard→INSERT 순서, 무관 managed probe/Employee 유지; dirty probe 자동 flush 없음; commit/abort 둘 다 검증 |
| T17 | REQUIRED PASS | 조직 3종 INACTIVE 연결 허용·actual 전보 ACTIVE 거부. actual create vs HR03 네 commit/rollback 일정, 독립 fixture·PID/XID·pg_blocking_pids. 별도 사전 FOR UPDATE 없는 version-only UPDATE가 SHARE에 막히고 양쪽 rollback |
| T18 | REQUIRED PASS | t18_am01: enabled/disabled SQL UPDATE 각1회, DTO=flush 후 같은 Tx DB=commit 후 DB version, status/reason/time/generation, HR 불변 |
| T19 | REQUIRED PASS | t19_t20 enable/disable 각각 동일 version 두 요청, actual User lock 대기→하나+1·패자 VERSION_CONFLICT |
| T20 | REQUIRED PASS | 단독/no-op2개/no-op선행/actual선행, stale 우선·invalid reason 거부. no-op 전후 전체 행 동일·flush0 |
| T21 | REQUIRED PASS | A: TERMINATED/ON_LEAVE×enable/disable4종. B: EMPLOYED에서 actual 퇴사×enable/disable×선행2×commit/rollback2=8일정. 한 Root 미커밋 lock 보유 중 다른 Root 실제 commit, SQL·pg_blocking_pids·최종 전체 행 |
| T22 | REQUIRED PASS | t22 old/fresh expected: Root 내 User 선조회→별도 실제 변경 commit→국소 refresh 최신값. 무관 probe attached·rollback/commit |
| T23 | REQUIRED PASS | Java reason1/500/보조평면500·invalid no-op, DB named23514 CHECK와 NULL쌍·23502·501varchar22001 구분 |
| T24 | REQUIRED PASS | PK/FK/company조합/canonical/status/version/generation/timestamp/NOTNULL 및 임시 test-only 기타 UNIQUE. SQLSTATE+이름, 지정 두 중복 이외 기술 오류 그대로. RESTRICT/nondeferrable catalog |
| T25 | REQUIRED PASS | post-flush create/enable/disable/퇴사·휴직 기존 User 양 명령 rollback, 실제 deferred commit abort, T21B 두 Root 개별 rollback·다른 Root commit 보존 |
| T26 | REQUIRED PASS | 실제 commit 뒤 test-only ACK wrapper 예외, 생성 retry UNIQUE/후속퇴사 Guard 오류·상태 stale/no-op retry. 실제 네트워크 전수 검증 아님 |
| T27 | REQUIRED PASS | 실제55P03 lock timeout/40P01 역순 deadlock/connection 종료/commit-time23514. 확정 abort·실제 commit 후 ACK wrapper 구분; 기술 오류를 업무 충돌·empty로 숨기지 않음 |
| T28 | REQUIRED PASS | scalar 단건/직원별·missing·퇴사연결, 동일createdAt loginKey+id 정렬·default20/size1/100/hasNext/음수/overflow/status, 실제 DB 장애 전파·stale managed getter 비혼합 |
| T29 | REQUIRED PASS | 기존 Modulith/ArchUnit + negative HR Entity 의존 fixture, negative persist-before-Guard SQL 순서 탐지 및 다른Connection PID/XID 구별. 실제 생성 Guard 필수와 상태 변경 Guard 비호출·HR SQL 부재 구분 |
| T30 | REQUIRED PASS | Foundation fresh V7, IamMigrationIT V6 업그레이드/제약/모든 행/checksum·validate/no seed, 기존 ORG/HR 전체 회귀와 원격 CI |
| T31 | DEFERRED | Role/UserRole·권한 부여/철회 미구현 |
| T32 | DEFERRED | Credential·로그인·기존 Session 접근 차단 및 AN-03 전체 통합 미구현 |
| T33 | DEFERRED | Actor·전체 Audit·Authorization·외부 관리 API 미구현 |
| T34 | DOCUMENTED LIMITATION | 운영 raw SQL/savepoint 전체 우회 방어, 불변 필드 DB 이력 보장, 페이지 간 snapshot, 원 요청 receipt 없음 |

R2 준비는 실제 HR-01 등록 서비스의 별도 고정 Clock 2026-10-01로 commit·종료하고 Employee v0/tail v0을 읽은 뒤, 경쟁 서비스의 Clock을 2026-10-11로 사용하는 두 단계다. 경쟁 도중 공유 Clock을 바꾸지 않는다. 별도 자정 기준일 시험만 의도적으로 Clock을 이동한다. T09~12/T17은 sleep 추정 대신 실제 pg_blocking_pids, 유한 latch, 서로 다른 PID/XID, 별도 조회 결과로 판별한다. SQL 로그의 PID/XID와 최종 행은 실행 로그 `I1 ...` 표식 뒤에 보존한다.

## 6. AN-03 AM-01 검증 범위

| AM-01 / 관련 QA | 이번 확인 | 보류 |
| --- | --- | --- |
| 저장 컬럼 | V7 BIGINT NOT NULL DEFAULT0·nonnegative CHECK, 생성 g0 | Session 저장·검증 |
| 실제 disable | User 한 UPDATE에 status/version/generation/reason/time, v+1/g+1. t18_am01 실제 SQL·같은 Tx/commit DB | A3-T03/04의 기존/늦은 Session 거부 |
| enable/no-op/stale/경쟁 | g 유지·실제 disable 승자만+1, 실패는 불변. T18~20 | A3-T05 중 Session 재사용 거부 |
| 확정 실패 | 실제 flush/commit 실패에서 v/g/reason 전부 rollback. T25/T21B | A3-T06의 Session 통합 |
| overflow | am01_overflowAndNegativeGeneration: Long.MAX_VALUE disable 전체 실패, DBnegative23514/직접overflow22003 별도 | 운영 rawSQL 전수 방어 |
| 모듈 소유권 | HR Employee generation 불변, 기존 HR04 AM02 회귀. A3-T35의 User writer/회귀 부분 | AN-03 전체 QA, 인증·세션 철회 완료 |

H34-T33의 **실제 계정 생성 대 퇴사 경쟁**은 이번 T09~12로 검증했다. H34-T34의 로그인·Session 차단을 완료 처리하지 않는다. ENABLED와 Guard 성공은 인증/인가 허가가 아니다.

## 7. 실제 개발 실패와 해결

1. **테스트 컴파일 오류**: jakarta.persistence.*와 JUnit의 Timeout 이름 충돌. 테스트 import를 EntityManager/EntityManagerFactory로 한정했다. 업무 정책 변경 없음.
2. **애플리케이션 구성 문제**: 두 Clock Bean이 Spring Modulith Moments의 unqualified Clock 의존성과 충돌하여 context 실패가 연쇄 발생했다. IAM Clock을 qualifier 전용 defaultCandidate=false로 바꿨다. 기존 HR Clock·업무 로직을 변경하지 않았다.
3. **테스트 계측 오류 2건**: FOR SHARE 실행 시점의 로그는 XID 할당 전이어서 뒤늦게 취득한 XID 필터로 해당 SQL이 누락됐다. test-only Root에서 먼저 PID/top-level XID를 확보하도록 수정했다. 자정 시험의 Mockito when 재설정이 이전 Answer를 호출해 Clock 호출을 2회로 집계하던 부분은 doReturn으로 바꿨다. production Clock 호출이나 잠금 규칙을 느슨하게 바꾸지 않았다.
4. 후속 계약 점검에서 같은 Transaction DB version 대조, metadata를 숨긴 physical readOnly 검사, 기타 UNIQUE 기술 오류, negative write-order fixture, 기존 종료·휴직 User의 두 명령 rollback과 실제 독립 SQL 증거를 보완했다. 이를 새 업무 버그 발견이나 과거 실패 로그로 꾸미지 않았다.

각 실패는 development-run의 실제 Maven 출력에 남긴다. 의도한 UNIQUE/CHECK/timeout/deadlock/연결종료의 WARN/ERROR 로그는 성공한 부정 테스트에도 존재하므로 JUnit의 failures/errors 집계와 구분한다.

## 8. 제출·한계

- 코드 커밋: `c6b5b624c61239d4366605e765c7d63e2b5a704a`. [작업 브랜치](https://github.com/kimini02/k-flow-erp/tree/codex/iam-01-employee-account), [Draft PR #7](https://github.com/kimini02/k-flow-erp/pull/7), base main, 30개 제출 파일.
- 해당 코드 HEAD의 독립 원격 실행: [Backend Run](https://github.com/kimini02/k-flow-erp/actions/runs/40440774006) / [verify Job](https://github.com/kimini02/k-flow-erp/actions/runs/40440774006/job/121980321996), event `pull_request`, **SUCCESS**.
- 실제 `./mvnw -B -ntp verify`: Surefire **58**, Failsafe **338**, 전체 **396 PASS**, failures/errors/skipped **0**, **BUILD SUCCESS** (2026-10-27 02:52:13 UTC).
- 실제 원격 로그: PostgreSQL **16.15**, Testcontainers **2.0.5**, Hibernate **7.4.5.Final**. 핵심 원문은 iam-01-run.txt 끝에 명시적 발췌로 보존한다.
- 이 문서의 원격 근거는 위 코드 SHA의 실행이다. Evidence 동기화 커밋 뒤 최종 HEAD의 Run/Job은 PR 설명과 최종 제출 보고에서 별도로 확인한다. 서로 다른 실행 결과를 동일 실행으로 표시하지 않는다.

- main 병합하지 않음. PM3가 코드·V7·동시성·Evidence를 독립 리뷰하여 GO/NO-GO 결정한다.
- I1-T31~33 DEFERRED, T34 DOCUMENTED LIMITATION 유지. 실제 네트워크 ACK 유실·commit-window 장애 전수 시험이 아니다. test-only wrapper와 DB 강제 종료 시험의 보장 범위를 운영 전체로 확대하지 않는다.
- loginKey 정정·재사용·삭제·재연결·직원 초기 이관·HR 휴직/복직/재입사, Role/Credential/Session/Authorization/Frontend/외부 API/Actor·전체 Audit/초기 관리자 미구현.
- 제출 범위와 무관한 `docs/architecture/.idea/`는 포함하지 않는다. 기존 ORG/HR Evidence·Migration 원문을 덮어쓰지 않는다.
