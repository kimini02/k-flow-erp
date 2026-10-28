# IAM-02 — Role 기준정보 Test Evidence

- 작성: 2026-10-28 (Asia/Seoul).
- 상태: **LOCAL VERIFIED / 원격 CI 확인 전**. PM3 독립 코드 리뷰·병합 판정 대기. main 병합 없음.
- 기준 main: `c6e7c3fd90bf09bf1f8abd094ee8fac58f7aaa61`. 착수 시 GitHub에서 재조회. 선행 [main CI](https://github.com/kimini02/k-flow-erp/actions/runs/40453630163) SUCCESS.
- 작업 브랜치: `codex/iam-02-role-master`.
- 범위: IAM 내부 Role 생성·rename·activate/deactivate·단건/코드/목록 조회. **Role-only** 완료 범위이며 UserRole 철회·Authentication·Authorization·외부 API 완료가 아니다.

## 1. 직접 읽은 승인 원문 및 기존 코드와의 차이

| 문서 제목 | 승인 상태 / 이번 적용 | 원문 |
| --- | --- | --- |
| IAM-02 Role 기준정보 상세설계 v0.1 — R2 ADOPTED | I2-D01~20 ADOPTED, PM R-01~03·Pro FR-01/02 포함. T01~28 REQUIRED, T29~33 DEFERRED, T34 LIMITATION | [Drive](https://drive.google.com/file/d/1dtugv3kDEsYqIb6-IRocvYV6eyszenq1/view) |
| IAM-03 UserRole 역할 부여·철회 Detailed Design — R2 ADOPTED | I3-D01~22 ADOPTED. 같은 deactivateRole Root에 전체 미철회 grant 철회를 연결해야 한다는 후속 계약 확인. 이번 구현 제외 | [Drive](https://drive.google.com/file/d/1ewt_bm_Mt6xU2Nrx0mGBcIeHfxW-8XiZ/view) |
| Organization HR Core IAM Core Authentication Authorization Implementation Plan v0.1 | IAM-02/03 R2 채택 동기화와 Slice 순서·완료 경계 확인. 과거 NOT_STARTED 기록을 현재 코드 상태로 간주하지 않음 | [Drive](https://drive.google.com/file/d/1GfbER7GIRq7pL6Wz0G9oYsPFh9OT5Q8F/view) |

세 원문을 Drive fetch로 직접 읽었다. GitHub 기준 main에는 ORG/HR/IAM-01 및 V1~V7이 존재하지만 Role/Role 관리 명령은 없었다. IAM-01의 내부 Root/Store/Clock/DTO 패턴을 참고하고, Role의 uppercase code·name·최근 변경 kind/reason·Role-only 경계를 별도 구현했다. 과거 PROPOSED 설계를 구현 기준으로 사용하지 않았다. BQ-R07은 최신 IAM-03에서 승인됐지만 이번에 grant 기능을 구현하지 않는다.

## 2. 실제 로컬 실행

- 작업 디렉터리 `backend/`, 명령 `JAVA_HOME=<JDK21> ./mvnw -B -ntp verify`.
- 종료 **2026-10-28 09:48:39 +09:00**, **BUILD SUCCESS**.
- Surefire **63 PASS**, Failsafe **394 PASS**, 총 **457 PASS**. failures/errors/skipped 각각 **0**.
- 기존 396개 + 신규 61개: RoleContractTest 5, RoleIT 55, RoleMigrationIT 1. QA ID 개수와 parameterized JUnit 실행 수는 다른 지표다.
- 실제 런타임: Java **21.0.2**, Spring Boot **4.1.1**, Spring Framework **7.0.9**, Hibernate **7.4.5.Final**, PostgreSQL **16.15**, Testcontainers **2.0.5**.
- 실제 로컬 이미지: `postgres:16`, digest `sha256:1a6ab3f5345eb6dbe04a1349529caabdb0ab09293a09590fad07b2246bfa4b54` (`docker image inspect` 조회). 원격 실행 이미지와 동일 digest라고 가정하지 않는다.
- [전체 성공 출력·JUnit XML 합계·개별 파일 SHA-256](iam-02-run.txt), [실제 개발 실행 출력](iam-02-development-run.txt). 개인 홈 경로와 실행 사용자만 `<LOCAL_HOME>`/`<LOCAL_USER>`로 치환했다. aggregate manifest hash를 주장하지 않는다.
- 기존 ORG-01~03, HR-01~04, IAM-01 전체 회귀 및 Modulith/ArchUnit/Foundation 통과. IamUserIT 71개가 실제 계정 생성↔전보/퇴사·FOR SHARE·UNIQUE·AM-01을, 기존 HR 테스트가 AM-02를 계속 검증한다. IAM-02가 인증 통합을 완성했다는 뜻은 아니다.

## 3. Production 변경 파일과 구현 선택

IAM 경로는 `backend/src/main/java/com/kflow/erp/iam/` 기준이다.

| 파일 | 책임 |
| --- | --- |
| `api/IamFailure.java` | 기존 코드 유지, ROLE_NOT_FOUND/ROLE_CODE_CONFLICT 두 오류 추가 |
| `domain/Role.java` | UUID v4·INACTIVE·@Version, 불변 식별/생성 근거, 실제 변경 kind/reason/time, no-op 불변 |
| `domain/RoleValues.java` | ASCII SPACE trim→ASCII 검증→Locale.ROOT uppercase, name Unicode 규칙 |
| `application/CreateRole.java` | 서버 결정 id/status/version을 입력에서 제외 |
| `application/RenameRole.java` | name·expectedRoleVersion·reason |
| `application/ChangeRoleStatus.java` | roleId·expectedRoleVersion·reason |
| `application/RoleService.java` | 독립 RC Root 명령 4개, commit 후 반환 |
| `application/RoleQueryService.java` | 내부 단건·코드·pagination |
| `application/RoleView.java` | 불변 상세 DTO, 접근 허가/부여 정보 없음 |
| `application/RoleListItem.java` | id/code/name/status/version만 노출 |
| `application/RolePage.java` | immutable list·hasNext |
| `application/RoleStore.java` | IAM 내부 저장 경계, Published Port 아님 |
| `infrastructure/JpaRoleStore.java` | 물리 Tx 검증·명시 FOR UPDATE·국소 refresh·scalar 조회·정밀 오류 변환 |
| `backend/src/main/resources/db/migration/V8__iam_role.sql` | Role 단일 테이블·10개 명시 PK/FK/UNIQUE/CHECK, seed 없음 |

- [RoleService](../../backend/src/main/java/com/kflow/erp/iam/application/RoleService.java)는 입력 검증 후 ambient Tx를 거부한다. 같은 manager/EMF/DataSource의 writable READ COMMITTED Root에서 물리 경계 확인→유한 대기 설정→Company 확인→Role 잠금/저장→flush→immutable DTO 순서이며 실제 commit 완료 이후만 반환한다. REQUIRES_NEW·retry·보상 삭제 없음.
- [JpaRoleStore](../../backend/src/main/java/com/kflow/erp/iam/infrastructure/JpaRoleStore.java)는 같은 Hibernate Connection으로 명시 PostgreSQL `FOR UPDATE` 후 해당 Role만 refresh한다. SQL에 잠금이 실제 존재하며 stale Entity와 무관한 dirty probe의 비간섭을 테스트했다. `PESSIMISTIC_FORCE_INCREMENT`·수동 version·전체 clear/detach·전역 flush mode 변경 없음.
- Root 기본 30s, lock 5s, statement 30s. 실제 timeout 시험은 test-only 800ms, 역순 deadlock locker 역시 test-only다. 운영 최대 부하/전역 deadlock 부재의 증거가 아니다.
- 실제 dirty UPDATE 하나로 version +1, no-op UPDATE 0. 생성 사유와 가장 최근 변경 하나만 저장한다. rename 뒤 이전 비활성화 사유가 덮이는 것은 D09의 승인된 최소 근거 한계다.
- `23505 + uq_iam_role_company_code`만 ROLE_CODE_CONFLICT. ORM optimistic 오류는 VERSION_CONFLICT. 다른 PK/FK/CHECK/UNIQUE·잠금/연결 장애는 기술 오류로 보존한다.
- 내부 scalar SELECT는 한 행 DTO를 한 SQL에서 읽는다. 목록 default20/max100·code/id ASC·size+1·hasNext, offset overflow/invalid status 거부. RoleReferenceQueries나 새 cross-module Published Port 없음.
- 동일 IAM 내부 경계 확인은 기존 User 구현과 같은 작은 adapter 패턴으로 유지했다. UserService/JpaIamStore/HR Production을 재설계하지 않고 범용 Transaction Framework도 만들지 않았다. 이로 인한 작은 코드 중복은 정책 변화 없이 Slice를 분리한 비용이다.
- [V8](../../backend/src/main/resources/db/migration/V8__iam_role.sql)은 DB 최소 ASCII trim/길이/pair 검증만 담당한다. Java의 모든 Unicode 공백/control/surrogate 검증을 DB가 대신한다고 주장하지 않는다. timestamp finite CHECK는 시계 단조성을 강제하지 않는다. 미래 UserRole 복합 FK용 중복 UNIQUE는 아직 추가하지 않았다.

## 4. I2-D01~20 구현 대응

| Decision | 구현 및 실제 확인 |
| --- | --- |
| D01 | IAM Role 소유, Company ID만 보유. T25 기존 Org/HR/User 실제 baseline 비간섭·ArchUnit |
| D02 | UUID v4/variant2 생성, id/company/code/createdAt 보존. T01/T08 |
| D03 | RoleValues.code와 V8 canonical CHECK. T02 unit/T05 integration/T22 SQL |
| D04 | Company+code 전 상태 UNIQUE·불변, Position과 namespace 독립, ADMIN 특례 없음. T03~05 |
| D05 | name codepoint1~100·정확한 trim·동명·INACTIVE rename. T06/T09 |
| D06 | create INACTIVE/v0, 두 상태 명령만. T01/T08 |
| D07 | 현재 Role 행 전이까지만 구현. 전체 미철회 UserRole 철회는 IAM-03 T29~31 DEFERRED |
| D08 | 동일 deactivateRole 후속 통합 경계 주석·이 문서. UserRole/NoOp revoke adapter/우회 명령 없음 |
| D09 | creationReason 불변, lastChange pair·kind·time actual만 갱신, no-op 입력도 검증. T07~10/T13 |
| D10 | standalone RC Root·실제 Connection 검증·commit 후 반환. T18/T26 |
| D11 | Role FOR UPDATE→국소 refresh, 최신 판단·다른 객체 비간섭. T17/T19a~c |
| D12 | stale 우선, 단일 dirty UPDATE v+1, no-op0. T10~14 |
| D13 | 자동 retry/receipt 없음. 확정 abort 전체 원복, 실제 commit 뒤 ACK wrapper 구분. T15/T16/T26 |
| D14 | V8 제약 + 지정23505만 변환. T20~22/T26 |
| D15 | scalar 조회/내부 pagination/장애 전파. T23/T24 |
| D16 | 기존 Company Published 조회만 사용, Role 서비스/DTO는 IAM 내부. Module/Layer/IamContract 회귀 |
| D17 | Role write 잠금 기반만 구현. 실제 grant SHARE·전체 revoke 연결은 IAM-03 Gate로 유지 |
| D18 | Role ACTIVE/ADMIN code로 접근 허가/Permission을 반환하지 않음. T25/내부 DTO·API 경계 |
| D19 | V8 additive, V1~7 bytes/checksums·기존 행/제약 유지, Role seed/미래 복합 UNIQUE 없음. T27 |
| D20 | 아래 REQUIRED/DEFERRED/LIMITATION 구분, 실제 local/remote Evidence 제출. 접근 차단 완료로 확대하지 않음 |

## 5. I2-T01~34 QA 대응

아래 `t...`는 [RoleIT](../../backend/src/test/java/com/kflow/erp/iam/RoleIT.java)의 실제 메서드 접두어다. 각 method의 parameterized 일정과 assertion 및 원문 로그를 함께 확인한다. 원격 CI는 §8에서 별도 실행으로 기록한다.

| QA | 분류·로컬 결과 | 실제 대응·판별 근거 |
| --- | --- | --- |
| T01 | REQUIRED PASS | t01: UUID v4/variant2, INACTIVE/v0, canonical/creationReason, null pair, created=updated; INSERT 및 같은 물리 PID/XID flush DB·반환 DTO·commit DB 비교 |
| T02 | REQUIRED PASS | RoleContractTest.t02, t05 입력 경계·터키 Locale, t20 DB code CHECK 및 VARCHAR33 구분 |
| T03 | REQUIRED PASS | t03_t04 commit=true: sales/SALES 두 독립 Root, 한 행 commit, 지정23505/constraint 업무 충돌 |
| T04 | REQUIRED PASS | t03_t04 두 일정: 미commit INSERT blocker 관찰, commit→충돌 / rollback→후행 성공, 최종 한 행 |
| T05 | REQUIRED PASS | t05: INACTIVE/ACTIVE/재비활성 code 예약, Position 같은 code 허용, ADMIN/001/A-B/A_B 구별 |
| T06 | REQUIRED PASS | RoleContractTest.t06, t05: name1/100/101 codepoint·emoji·동명·invalid Unicode, Application/DB 보장 구분 |
| T07 | REQUIRED PASS | t05 invalid reason 모든 명령+no-op, t07 valid1/500 BMP/500emoji 모든 명령, no-op 저장 근거 보존 |
| T08 | REQUIRED PASS | t01 actual activate→deactivate→reactivate 동일 정체성·v 증가. grant 복원 시험 아님 |
| T09 | REQUIRED PASS | t01/t05 INACTIVE rename, 상태/불변값 보존, T16 rename rollback |
| T10 | REQUIRED PASS | t01 한 dirty UPDATE/WHERE version, 같은 Tx PID/XID·DB와 commit 뒤 DTO/DB version 및 근거 대조 |
| T11 | REQUIRED PASS | t11~14 + t15: stale 요청은 현재 값이 같아도 충돌, rename/activate/deactivate retry 모두 검증 |
| T12 | REQUIRED PASS | t11~14 14개 일정 중 rename/rename, 두 실제 상태 전이, rename↔activate/deactivate 양방향. 선행 commit 뒤 후행 VERSION_CONFLICT |
| T13 | REQUIRED PASS | t11~14 rename/activate/deactivate no-op 두 개의 순차 성공·무UPDATE·전체 값 보존 |
| T14 | REQUIRED PASS | t11~14 ACTIVE/INACTIVE에서 no-op 선행/실제 전이 선행을 분리, 전자는 둘 다 성공·후자는 stale 거부 |
| T15 | REQUIRED PASS | t15 실제 manager.commit 후 응답단 test wrapper. 생성 재시도 code 충돌, 실제 rename/activate/deactivate 후 old version 충돌. 실제 네트워크 장애 전수 시험 아님 |
| T16 | REQUIRED PASS | t16 네 명령 post-flush 실패 전체 row rollback + t19a 실제 후행 대기 해제·원래 version으로 진행 |
| T17 | REQUIRED PASS | t17 두 fixture: A managed v0→B v1 commit→A lock fresh. old expected 거부/fresh expected 성공; dirty unrelated probe 미flush·미detach·flush mode 보존 |
| T18 | REQUIRED PASS | t18 ambient 모든 명령/wrong manager before Company, 실제 readonly/RR/metadata mismatch/null metadata 실제RC 성공·RR/readonly 거부·확인 연결 장애; deferred trigger로 실제 commit 실패 후 성공 반환 없음 |
| T19 | REQUIRED PASS | t19a 같은 roleId 대기, t19b 다른 roleId 선행 해제 전 commit, t19c 사전 FOR UPDATE 없는 비키 status/version writer 대기. 각 2개 commit/rollback fixture, 총6개 별도 실행 |
| T20 | REQUIRED PASS | t20 실제 SQLSTATE/constraint: 지정code UNIQUE만 변환, PK/다른 UNIQUE/FK/CHECK 그대로. RoleContractTest.t20 optimistic/unknown 분류 |
| T21 | REQUIRED PASS | t21 실제 Company 입력 불일치/0개 configuration 구분 및 rollback, upstream configuration 오류 전파; t20 FK23503 별도. 복수 Company는 기존 singleton 제약이 차단하므로 다법인 seed/production 우회 없음 |
| T22 | REQUIRED PASS | t20 code/name/status/version/reason/pair/finite timestamp별23514, 양쪽null 허용/한쪽null 거부, NOT NULL23502·길이22001 분리, 보조평면 경계·RESTRICT/NOT DEFERRABLE |
| T23 | REQUIRED PASS | t23 단건·code 각 한 SQL, stale Entity와 혼합 없음, INACTIVE/없는ID/다른종류UUID, 실제 DB 오류 전파. t25 실제 User UUID로 Role 조회 empty |
| T24 | REQUIRED PASS | t23 default20/max100, page0/끝/범위밖·hasNext·code 순서·status, invalid size/page/overflow/status 거부, DTO 최소 필드 unit |
| T25 | REQUIRED PASS | t25 실제 HR10월1일 등록 commit→IAM10월11일 계정 생성 baseline, Role 명령 전후 Org/HR/User 모든 값·두 generation·다른 Role 불변, SQL/모듈 경계, UserRole 테이블 부재 |
| T26 | REQUIRED PASS | t26 실제55P03/40P01/연결종료, 확정 실패 full rollback·기술오류 유지; t18 commit abort와 t15 commit 후 ACK wrapper를 구분. read 오류는 empty로 바꾸지 않음 |
| T27 | REQUIRED PASS | [RoleMigrationIT](../../backend/src/test/java/com/kflow/erp/iam/RoleMigrationIT.java): V7→V8 모든 기존 행/제약/checksum·no seed·10개 제약/2개 index/C collation/NOT NULL. Foundation fresh8 tables/entities/Flyway·Hibernate validate |
| T28 | REQUIRED PASS (local) | 전체457 PASS, 기존396 유지. 원격 결과는 §8. 외부 업무 API 없음(OpenAPI paths empty), UserRole/철회 stub 없음 |
| T29 | DEFERRED | 실제 신규 grant/Role 재활성 후 기존 grants 비복원은 IAM-03 통합 |
| T30 | DEFERRED | 실제 grant↔deactivate 네 일정, 같은 ACTIVE Role의 두 grant SHARE 공존·비키 UPDATE 충돌은 IAM-03 |
| T31 | DEFERRED | DISABLED/TERMINATED 이후 남은 미철회까지 정확한 ID 집합·전체 원자 철회·부분/전체 flush rollback·비대상 보존은 IAM-03 |
| T32 | DEFERRED | AN/AZ 실제 Session·요청별 권한 차단/다른 Allow 보존 |
| T33 | DEFERRED | 영향 안내·승인/관리자 확인·마지막 관리자 보호·전체 Audit·운영 공개 Gate |
| T34 | DOCUMENTED LIMITATION | 페이지 간 snapshot·원 요청 receipt·전체 이력·운영 raw SQL/savepoint 전수 우회 방어 없음 |

T19 세 시험은 동일 행 대기와 다른 행 비경합을 섞지 않는다. test-only bare writer는 PK/code를 변경하지 않고 최종 rollback한다. 실제 roleId·각 Root PID/XID·SQL/bind·pg_blocking_pids 및 최종 행이 `I2 T19a/b/c` 로그에 남는다. latch는 진행 순서를 제어하고 짧은 polling은 실제 DB blocker가 나타났는지를 확인하는 데만 사용한다. sleep 자체를 잠금 증거로 삼지 않는다.

## 6. Migration·기존 보장 보존

- V1~V7 파일 bytes는 기준 main의 `git show` 결과와 일치하며 개별 SHA-256을 실행 로그에 기록했다. 과거 Migration 수정 없음.
- V7 업그레이드 fixture에는 기존 Company/Site/Department/Position과 EMPLOYED/ON_LEAVE/TERMINATED 직원·Assignment·연결 User, 기존 version/generation 값이 존재한다. 모든 컬럼·기존 제약 정의·Flyway checksum이 V8 전후 같음을 비교했다. 이는 업그레이드 격리 fixture이며 HR 휴직/재입사 기능이나 계정 생성 정책 추가가 아니다.
- 기존 테스트 수정은 최신 schema 기대값8·V3→최신 migration 수5, Foundation의 정확한 8개 table/entity, IAM-01의 V6→V7 검증을 `target(7)`로 고정한 것뿐이다. 해당 테스트 뒤 실제 애플리케이션은 V8까지 적용해 validate한다. 이전 행/제약/회귀 assertion을 삭제하지 않았다.
- Production 기존 ORG/HR/IAM-01 정책·Frontend·외부 HTTP API·빌드 설정 변경 없음. 무관한 `docs/architecture/.idea/`는 제외한다.

## 7. 실제 개발 과정·Trade-off

1. 최초 단위 5개 PASS. 최초 집중 통합 53개 중 **4 FAIL / 49 PASS**, errors/skips0: post-flush 주입 예외를 직접 IllegalStateException으로 기대했지만 Spring `@Repository` 예외 변환이 InvalidDataAccessApiUsageException으로 감쌌다. **테스트 기대값 문제**이며 Production 업무 버그로 기록하지 않는다. wrapper 타입 + root cause 타입/메시지 + 모든 DB 값 rollback을 함께 검증하도록 수정했다. 업무 동작·예외 분류를 테스트에 맞춰 느슨하게 바꾸지 않았다.
2. reason의 유효1/500/emoji500 모든 명령 시험3개 및 같은 물리 PID/XID 대조를 보강했다. 집중 재실행 unit5+IT56 PASS, 최종 전체63+394 PASS. 이 추가 검토를 과거 실패나 새로운 업무 장애로 꾸미지 않았다.
3. 한 번의 root Maven 실행이 만든 root `build/generated-spring-modulith/javadoc.json`만 제거했다. 최종 전체 검증은 `backend/`에서 수행했고 생성 파일·IDE 파일을 제출하지 않는다.
4. 원자성 실험의 deferred trigger·강제 연결 종료·역순 deadlock locker·ACK wrapper는 테스트 전용이다. production Trigger/자동 retry/보상 삭제/운영 장애 Framework를 추가하지 않았다.
5. 이번 실행에서 새 Production 업무 버그는 발견되지 않았다. 실제 runtime 잠금/UNIQUE/rollback은 검증됐으나 부하 성능·모든 운영 장애·보안 완료를 주장하지 않는다.

## 8. GitHub 제출·후속 경계

원격 제출 및 최종 HEAD CI는 실제 실행 후 동기화한다. 로컬 PASS를 원격 PASS로 기록하지 않는다.

- PM3 리뷰 후 별도 병합 판단. 이번 작업에서 main 병합하지 않는다.
- IAM-03이 들어올 때 **동일 deactivateRole Root에 전체 미철회 grant 처리**를 연결해야 한다. Role-only artifact와 신규 grant writer의 혼합 배포, 이전 상태만 바꾸는 우회 경로, NoOp revoke adapter는 허용하지 않는다.
- I2-T29~33 DEFERRED/T34 LIMITATION 유지. BQ-R01~04 승인 완료, BQ-R05/06/08 운영 상세 후속 OPEN, BQ-R07은 최신 IAM-03 R2 채택 정책을 따른다.
- Role ACTIVE나 ADMIN code는 실제 로그인/접근 허가가 아니다. IAM-03, AN/AZ, 관리자 보호·Actor/전체 Audit, Frontend·외부 API, seed·bootstrap·다음 Slice 미구현.
