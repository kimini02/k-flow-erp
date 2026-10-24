# ORG-02 Department / Position — Test Evidence

- 상태: **LOCAL VERIFIED / REMOTE CI VERIFIED / PM3 코드 리뷰·최종 판정 대기**. 구현 커밋의 원격 실행 근거는 아래 제출 절에 기록했다.
- 실행일: 2026-10-24 (Asia/Seoul).
- GitHub 기준: `main`, `ad1c2e06d7d4b3755f18335e8e73b98c9fa3f985`. 구현 전 `git ls-remote origin HEAD refs/heads/main`으로 원격 기본 HEAD와 로컬 일치를 확인했다.
- 구현 브랜치: `codex/org-02-department-position`. 최초 로컬 검증 당시에는 위 기준선 이후 미커밋 working tree였으며, 제출 단계에서 구현 커밋 `0391dc0db2c3d0658358a86ccd0627df1e8c2e18`로 commit/push했다. 기준선 SHA와 구현 SHA를 구분한다.
- [실행 로그 및 개별 파일 SHA-256](organization-org-02-run.txt). 로컬 경로·사용자명만 명시적 placeholder로 치환했다. 테스트 결과 줄은 생략하거나 새로 만들지 않았다. aggregate manifest hash 주장은 하지 않는다.

## 직접 읽은 Google Drive 설계

2026-10-24 연결된 Drive 도구로 원문을 fetch하고 ORG-02 계약, ORG-03 경계, Architecture의 소유권/Published Port, Implementation Plan의 선행·종료조건을 직접 읽었다. 링크나 파일명만 보고 승인 상태를 추정하지 않았다.

| 제목 / 파일 | 본문에서 확인한 상태 | 원문 |
| --- | --- | --- |
| K-Flow ERP ORG-02 Department + Position Detailed Design v0.1 — ADOPTED (R2) | 2026-10-08 O2-D01~14 전부 ADOPTED, R-01~03 하위 테스트 계약 포함. 문서의 NOT_STARTED/NOT_RUN은 작성 당시 상태 | [ORG-02 R2](https://drive.google.com/file/d/1TYFffMwHuRFOkkYjPWSd1zsKzYxEDh0a/view) |
| K-Flow ERP ORG-03 Detailed Design v0.1 — ADOPTED (R2) | 2026-10-10 O3-D01~18 ADOPTED, 실제 구현/검증 후속. 이번에 구현하지 않음 | [ORG-03 R2](https://drive.google.com/file/d/1oB7KPNjMRLyjUmjBnMI_El35toJ1TzQ6/view) |
| K-Flow ERP Architecture Overview v0.1 (`architecture-az-adopted-sync.md`) | 2026-10-15 최신 AZ 채택 동기화 포함. AQ-01 소유권/Published Port 등 채택 범위와 나머지 DRAFT/OPEN을 구분. 전체 내용을 일괄 ADOPTED로 간주하지 않음 | [Architecture](https://drive.google.com/file/d/15TZeY1hgLowgAuhoe9t0--Qy2xuZcwFf/view) |
| Organization HR Core IAM Core Authentication Authorization Implementation Plan v0.1 (`organization-hr-iam-implementation-plan-az-adopted-sync.md`) | 2026-10-15 최신 채택 동기화 포함. ORG-02 R2 ADOPTED 및 ORG-02 → ORG-03 → HR → IAM 실제 구현/검증 순서 확인 | [Implementation Plan](https://drive.google.com/file/d/1GfbER7GIRq7pL6Wz0G9oYsPFh9OT5Q8F/view) |

원문 전체를 최신 구현 상태로 덮어쓰거나, 후속 IAM/AN/AZ의 설계 승인을 이번 기능 구현 승인으로 확대하지 않았다.

## Drive와 기준 코드의 차이 및 반영

| 기준 코드/문서 | 승인 설계 | 이번 변경 |
| --- | --- | --- |
| Organization은 Company/Site만 구현, V1만 존재 | Company 직접 소속 Department와 회사 공통 Position | 독립 Entity 두 개와 additive V2. Department.siteId/계층, Position.departmentId/급여/Role 없음 |
| 저장소 Implementation Plan은 ORG-02 세부 계약이 후속인 과거 문서 | O2-D01~14 및 R2 하위 검증 채택 | Drive R2를 기준으로 구현. 과거 후보를 선택하지 않음 |
| Published 단건 조회 2개 | 단건 4개, 목록은 내부 | `findDepartment/findPosition` 추가. 내부 목록은 기본 20/최대 100, status, code/id 정렬 |
| ORG-01 code/name, optimistic completion 검증 | 같은 규칙 재사용, INACTIVE 조회/rename, stale 우선 | 기존 값 규칙과 TransactionTemplate REQUIRED 유지. 새 rename은 누락 version도 INVALID_VERSION으로 거부 |
| Site 지정 UNIQUE 오류 변환 | Entity별 지정 UNIQUE 이름만 해당 CODE_CONFLICT | Department/Position 지정 이름 추가. PK/FK/CHECK/기타·미상 제약 및 기술 실패는 중복으로 오역하지 않음 |
| Foundation 테이블/Entity/V1 개수 검사가 ORG-01에 고정 | 승인된 ORG-02까지만 존재 | 정확한 허용 목록을 4개 Entity/테이블, Migration 2개로 갱신. 검사 삭제/완화하지 않음 |

ORG-02 문서가 작성될 때 후속 OPEN이었던 사용 중 비활성화는 이후 ORG-03 R2에서 ADOPTED다. 이 변경을 다시 OPEN으로 돌리지 않는다. 다만 상태 전환/Guard의 구현과 테스트는 여전히 이번 범위 밖이다.

## 실제 실행

환경: Java 21.0.2, macOS arm64, Docker 27.4.0, Testcontainers PostgreSQL `postgres:16` (실행 로그의 서버 버전 16.15). H2 대체·테스트 skip 없음.

최종 명령 (`backend` 디렉터리):

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp verify
```

| 실행 | 결과 | 실제 관찰 |
| --- | --- | --- |
| 1 | 실패 1 / 오류 0 / skip 0 | FoundationIT의 기존 2개 테이블 기대값이 신규 2개를 거부. ORG-02 신규 통합 25개와 기존 ORG-01 통합 22개 통과 |
| 2 | 동일 실패 1 / 오류 0 / skip 0 | 수정 스크립트의 상대경로 오류로 기대값이 바뀌지 않았는데 검증이 실행됨. 새 제품 결함으로 기록하지 않음 |
| 3 | **BUILD SUCCESS, 92 PASS / 실패 0 / 오류 0 / skip 0** | 2026-10-24T19:33:01+09:00 완료. Surefire 43 + Failsafe 49. XML 보고서와 콘솔 결과 대조 |

| 검증 클래스 | 최종 실행 수 |
| --- | ---: |
| OrganizationValuesTest | 34 |
| OrganizationContractTest | 2 |
| ModuleArchitectureTest | 1 |
| LayerArchitectureTest | 3 |
| FrameworkProblemHandlerTest | 3 |
| DepartmentPositionIT | 21 |
| OrganizationConstraintTranslationIT | 3 |
| OrganizationV2MigrationIT | 1 |
| OrganizationIT (기존 ORG-01) | 22 |
| FoundationIT | 2 |

필수 명세 17항목은 JUnit 17건이라는 뜻이 아니다. 하나의 parameterized test 또는 한 테스트의 여러 assertion이 여러 하위 계약을 검증한다.

## R2 검증 대응

테스트 경로는 `backend/src/test/java/com/kflow/erp/organization/`이며 아래 메서드명으로 찾을 수 있다.

| 승인 명세 | 실제 검증 근거 |
| --- | --- |
| T01/T04 독립성·namespace | `migrationAndIndependentNamespacesPreserveCompanyAndSite`: Site 0건에서 양 Entity 생성, UUIDv4/ACTIVE/version0, 같은 코드 공존, Company 원본 불변 |
| T02/T03 경쟁 중복 | `concurrentCanonicalRegistrationCommitsExactlyOne`: 실제 별도 트랜잭션을 rendezvous로 맞춰 양 Entity 각각 1 commit + 정확한 CODE_CONFLICT |
| T05/T14 INACTIVE | `codesNamesInactiveReuseAndRegistrationRetry`: fixture 상태에서 중복 거부, 단건 사실 조회, 이름 정정 허용과 status/ID 보존 |
| T06/T07 입력 경계 | `invalidValuesRollbackAndUnicodeBoundariesPersist`: 32/33자, TAB/CR/LF/NBSP/전각/한글/내부공백, 100/101 code point, 등록·rename 거부와 미저장. name 중복 허용은 namespace/INACTIVE/목록 fixture로 확인 |
| T08/T10/T11 및 R-03 retry | `renameStableIdentityNoopStaleAndLostResponseRetry`: 불변 필드, 반환 DTO/DB version +1, no-op xmin/updatedAt 보존, stale 같은 이름도 거부, null/음수 version 거부 |
| T09/R-03 실제 변경 경쟁 | `concurrentRealRenameHasOneWinnerAndReturnsCommittedVersion`: 두 실제 rename 중 1승, 패자 rollback, 승자 이름과 반환/DB version +1 |
| R-03 concurrent no-op | `concurrentNoopsBothSucceedWithoutChangingRow`: 양쪽 성공, version/updatedAt 유지 |
| T12/R-03 완료 시점 충돌 | `noopConflictIsTranslatedAtActualIndependentTransactionCompletion`: 실제 JPA OPTIMISTIC 등록 뒤 경쟁 rename commit, no-op 완료에서 VERSION_CONFLICT, 패자의 성공 DTO 반환 없음 |
| T13/R-02 조회 오류 구분 | `companyErrorsMissingAndDatabaseFailuresAreNotEmpty`: 정상 empty, 다른 Company, 0/복수 Company fixture, 실제 SQL 장애 전파. 반대 종류 ID는 namespace 테스트에서 양방향 empty 확인 |
| T17 공개 경계 | 기존 Modulith/ArchUnit 통과, OrganizationContractTest의 정확히 4개 단건 계약/최소 DTO/내부 Page, 허용 명령·불변 필드 검사 |
| T18 내부 pagination | `internalPaginationHasStableOrderFiltersAndInputLimits`: 양 Entity 각각 고정 fixture의 전체 페이지 순서·중복/누락 없음, default/최대/최소, status, hasNext, 음수/overflow/잘못된 크기 거부 |
| T20 DB 직접 우회 | `databaseChecksRequiredFieldsAndForeignKeysRollback`: 실제 PostgreSQL NOT NULL/CHECK/PK/FK, transaction rollback, 부모 delete/id update RESTRICT |
| R-01 변환 positive/negative | OrganizationConstraintTranslationIT: 실제 PostgreSQL의 지정 UNIQUE/PK/FK/CHECK 오류를 얻어 production `JpaOrganizationStore.flush()` 변환 경로 검증. 이 경로의 EntityManager는 테스트 double이며 실제 ORM flush를 통한 negative 위반 발생이라고 과장하지 않음. 기타 UNIQUE/미상 이름/connection/programming 오류는 격리 예외 fixture. 실제 Application 동시 중복은 DepartmentPositionIT로 별도 검증 |
| R-03 등록 응답 유실 | `codesNamesInactiveReuseAndRegistrationRetry`: commit 후 결과를 사용하지 않고 동일 code 재시도 → CODE_CONFLICT/기존 행 유지. HTTP 전송 장애를 주입한 테스트는 아님 |
| Migration | DepartmentPositionIT/FoundationIT의 빈 DB V1→V2 및 Hibernate validate, OrganizationV2MigrationIT의 기존 V1 company/site 보존·V1 checksum 보존·V2 적용·반복 migrate 0·Flyway validate·Hibernate validate |

## 한계와 후속

- 최초 로컬 검증 당시 원격 CI는 NOT_RUN이었다. 이후 아래 구현 커밋의 GitHub Actions 성공을 직접 확인했다. PM3 코드 리뷰와 전체 ORG-02 최종 완료 판정은 아직 대기 중이다.
- REQUIRED Transaction의 commit-time 예외 변환 보장은 현재 독립 Organization Use Case에 한한다. 외부 상위 Transaction 참여 시 실제 commit 소유자/변환 계약은 후속 상세설계 대상이다.
- O2-T15/16은 ORG-03/HR 후속 검증이다. 상태 전환/Guard/EmployeeAssignment 구현 없음.
- O2-T19는 DOCUMENTED LIMITATION: offset pagination의 여러 요청 간 snapshot 일치는 보장하지 않는다.
- 교차 모듈 Java 경계 검사는 SQL 우회를 모두 검출한다는 보장이 아니다. 이번 저장 SQL과 Migration은 organization 테이블만 사용한다.
- 현재 권한·감사·외부 업무 API·Frontend 연동 없음. Foundation OpenAPI의 업무 paths가 비어 있는 검사 유지.
- V1 Migration·Company/Site Entity·값 검증 규칙·Frontend·HR/IAM 코드는 변경하지 않았다. 기존 `docs/architecture/.idea/`는 그대로 유지했다.

## PM3 제출 전 검토 — 2026-10-24

기존 구현 22개 소스/테스트 파일의 SHA-256이 보존된 로컬 실행 Evidence와 전부 일치함을 확인했다. 이번 제출 준비에서 Java/SQL/테스트/설정을 수정하지 않았다. `origin/main`은 여전히 `ad1c2e06d7d4b3755f18335e8e73b98c9fa3f985`다. 이 SHA는 UI 병합 기준선이며 ORG-02 구현 커밋이 아니다.

| 채택 Decision | 코드/검증 대조 결과 |
| --- | --- |
| O2-D01 | Department.companyId만 존재, Site/계층 관계 없음. 독립 생성 및 계약 검사 |
| O2-D02 | Position.companyId만 존재, Department/급여/권한/순위 없음 |
| O2-D03 | 서버 UUIDv4, id/companyId 불변 및 rename 전후 보존 |
| O2-D04 | 기존 OrganizationValues.code 재사용, canonical code 저장·불변 |
| O2-D05 | Entity별 모든 상태 UNIQUE(company_id,code), INACTIVE 재사용 거부 |
| O2-D06 | 기존 name 규칙 재사용, 동명 허용·INACTIVE rename 허용 |
| O2-D07 | 최초 ACTIVE, ACTIVE/INACTIVE 저장·조회만 제공 |
| O2-D08 | usage/상태 명령/배정 Guard 미구현. ORG-03 채택을 이번 구현으로 확대하지 않음 |
| O2-D09 | BIGINT @Version, expectedVersion, 실제 변경 +1, no-op 완료 시점 검증. 외부 상위 Transaction 한계 유지 |
| O2-D10 | Hard Delete/전파 삭제 명령 없음 |
| O2-D11 | Published 단건 4개, INACTIVE 사실 조회, 내부 Entity/Page 미노출 |
| O2-D12 | 내부 목록 default 20/max 100, status, code/id 정렬, overflow 거부 |
| O2-D13 | V1 원문 불변, additive V2, 지정 UNIQUE만 CODE_CONFLICT 변환 |
| O2-D14 | 아래 REQUIRED 17항목과 기존 회귀 대응. 원격 CI 판정은 실제 실행 결과를 별도 기록 |

### REQUIRED QA 17개 — 개별 대응표

`D` = [DepartmentPositionIT](../../backend/src/test/java/com/kflow/erp/organization/DepartmentPositionIT.java). `D`의 parameterized 메서드는 Department와 Position 각각 실행한다. 테스트 개수와 요구사항 개수는 다르다. T17은 현재 모듈/계약 검사와 저장 코드 검토이며, 별도의 잘못된 모듈을 production 코드에 주입한 실행이라고 주장하지 않는다.

| QA | 검증 대상 | 실제 테스트 메서드 / 검사 |
| --- | --- | --- |
| O2-T01 | 등록·Company 소속·Site 독립 | D.migrationAndIndependentNamespacesPreserveCompanyAndSite |
| O2-T02 | Department 동시 canonical code 등록 | D.concurrentCanonicalRegistrationCommitsExactlyOne(DEPARTMENT) |
| O2-T03 | Position 동시 canonical code 등록 | D.concurrentCanonicalRegistrationCommitsExactlyOne(POSITION) |
| O2-T04 | Entity별 코드 namespace | D.migrationAndIndependentNamespacesPreserveCompanyAndSite + codesNamesInactiveReuseAndRegistrationRetry |
| O2-T05 | INACTIVE 코드 재사용 금지 | D.codesNamesInactiveReuseAndRegistrationRetry |
| O2-T06 | 코드 정규화·경계·DB CHECK | D.invalidValuesRollbackAndUnicodeBoundariesPersist + databaseChecksRequiredFieldsAndForeignKeysRollback |
| O2-T07 | Unicode 이름·동명·정정 | D.invalidValuesRollbackAndUnicodeBoundariesPersist + codesNamesInactiveReuseAndRegistrationRetry |
| O2-T08 | Stable ID/불변 필드·version +1 | D.renameStableIdentityNoopStaleAndLostResponseRetry |
| O2-T09 | concurrent real rename 1승/1충돌 | D.concurrentRealRenameHasOneWinnerAndReturnsCommittedVersion |
| O2-T10 | stale version 변경·동일 이름 거부 | D.renameStableIdentityNoopStaleAndLostResponseRetry |
| O2-T11 | 정상 no-op version/시각 보존 | D.renameStableIdentityNoopStaleAndLostResponseRetry + concurrentNoopsBothSucceedWithoutChangingRow |
| O2-T12 | no-op 실제 완료 시점 경쟁 | D.noopConflictIsTranslatedAtActualIndependentTransactionCompletion |
| O2-T13 | Company 구성/오참조/FK 구분 | D.companyErrorsMissingAndDatabaseFailuresAreNotEmpty + databaseChecksRequiredFieldsAndForeignKeysRollback |
| O2-T14 | INACTIVE 사실 조회·이름 정정 | D.codesNamesInactiveReuseAndRegistrationRetry |
| O2-T17 | Module/Published 경계 | ModuleArchitectureTest, LayerArchitectureTest, OrganizationContractTest 및 저장 SQL 검토 |
| O2-T18 | 순서·pagination·filter | D.internalPaginationHasStableOrderFiltersAndInputLimits |
| O2-T20 | DB 직접 우회·rollback | D.databaseChecksRequiredFieldsAndForeignKeysRollback + OrganizationConstraintTranslationIT |

FoundationIT는 PostgreSQL major 16, 정확한 테이블 목록, 정확한 Entity 목록, Migration 개수, pending 없음, Flyway validate, EntityManagerFactory, ddl-auto=validate, open-in-view=false, 실제 HTTP OpenAPI/Swagger 및 빈 업무 paths 검사를 유지한다. Entity/테이블은 정확히 Company/Site/Department/Position, 적용 Migration은 정확히 2개로 확장했으며 `contains`/skip 등으로 검사를 완화하지 않았다.

V1→V2는 OrganizationV2MigrationIT에서 기존 회사/사업장 행·V1 checksum 보존과 Flyway/Hibernate validate를 확인한다. ORG-01 OrganizationIT 22개와 Modulith/ArchUnit은 그대로 유지했다. 외부 관리 Controller/API는 추가하지 않았다.

발생 문제의 분류: 보존된 개발 실행에서 실제 ORG-02 업무 로직 버그 수정은 없었다. FoundationIT 실패는 승인된 schema 확장에 따른 테스트 기대값 갱신이며, 반복 실패는 수정 스크립트의 상대경로 실수다. 이를 동시성 또는 데이터 무결성 버그를 발견·해결한 사례로 바꾸어 기록하지 않는다. PM3의 코드 리뷰와 최종 판정은 별도다.

커밋 직전 재실행: `./mvnw -B -ntp verify`, 완료 시각 `2026-10-24T20:11:32+09:00`, **92 PASS / 실패 0 / 오류 0 / skip 0** (Surefire 43 + Failsafe 49). XML 보고서와 로그 결과를 대조했으며 실행 로그에 추가 보존했다.


## GitHub 제출 및 원격 CI — 2026-10-24

- [작업 브랜치](https://github.com/kimini02/k-flow-erp/tree/codex/org-02-department-position), [Draft PR #2](https://github.com/kimini02/k-flow-erp/pull/2), base `main`. main 병합·브랜치 삭제 없음.
- 구현 커밋: [`0391dc0db2c3d0658358a86ccd0627df1e8c2e18`](https://github.com/kimini02/k-flow-erp/commit/0391dc0db2c3d0658358a86ccd0627df1e8c2e18). ORG-02 소스/테스트 22개와 Evidence 2개만 포함했다.
- Backend push [실행 40088977977](https://github.com/kimini02/k-flow-erp/actions/runs/40088977977), [verify job](https://github.com/kimini02/k-flow-erp/actions/runs/40088977977/job/120857565382): **SUCCESS**. Actions metadata의 `headSha`는 위 구현 커밋과 일치한다.
- 실제 명령: `./mvnw -B -ntp verify`. 로그 완료 시각 `2026-10-24T11:13:47Z` (20:13:47 KST).
- 실제 결과: **92 PASS / failures 0 / errors 0 / skips 0** = Surefire 43 + Failsafe 49. 각 클래스 실행 수는 위 로컬 최종 실행 표와 일치한다. ORG-01 22개, Foundation 2개, Modulith/ArchUnit 포함.
- 로그에서 Testcontainers **2.0.5**, `postgres:16` pull·container start, Flyway의 **PostgreSQL 16.15**, V1/V2 적용을 직접 확인했다. H2 대체 또는 테스트 skip으로 얻은 결과가 아니다.
- 같은 구현 커밋의 pull_request [실행 40089002784](https://github.com/kimini02/k-flow-erp/actions/runs/40089002784)도 metadata에서 SUCCESS를 확인했다. 위 테스트 수와 원문 보존 근거는 push 실행 로그다.
- 원격 CI 실패·원인 수정 없음. 이번 제출 검증 중 Java/SQL/테스트/설정 변경 없음. 기존 로컬 구현을 제출했으며 이 절과 원격 실행 기록은 Evidence 후속 커밋으로 추가한다.
- [run.txt](organization-org-02-run.txt)에 `gh run view 40088977977 --repo kimini02/k-flow-erp --log`로 받은 출력 중 실제 verify step 전체를 추가 보존했다. checkout·cache 등 다른 step은 제외했음을 표시했다. 실제 실행 출력을 다시 작성하거나 추정 PASS를 추가하지 않았다.
