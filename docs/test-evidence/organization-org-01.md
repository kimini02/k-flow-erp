# ORG-01 회사와 사업장 — 실제 검증 근거

- 결과: **LOCAL VERIFIED — ORG-01 필수 검증 통과 / 원격 CI NOT_RUN**
- 실행일: 2026-10-07, Asia/Seoul. 최종 verify 종료 15:12:52 +09:00.
- 기준 브랜치: `codex/backend-foundation`
- 기준 HEAD: `88347ded6d941c497ccefaf1d8447ce28d196d95`
- 검증 대상: 위 HEAD + 이번 ORG-01 **미커밋** 변경. 위 HEAD 자체에 ORG-01 구현이 있었다는 의미가 아니다. commit/push/PR/merge/배포 없음.
- 채택 기준: [ORG-01 상세설계 O1-D01~10 ADOPTED](../specs/organization-org-01-company-site-detailed-design-v0.1.md), [구현 계획](../plans/organization-hr-iam-implementation-plan-v0.1.md), [ADR 0001](../adr/0001-organization-employee-account-boundaries.md).
- 설계 패치는 준비 HEAD와 일치하는 상태에서 `git apply --check` 통과 후 적용. ADR/ERD 및 E-01/E-02, E-03 이후 OPEN은 수정하지 않음.
- 기존 Frontend 미추적 파일은 작업 전부터 존재했다. 이번 ORG-01에서 Frontend·다른 모듈·pom 버전·CI 설정을 변경하지 않음.

## 1. 검증 입력과 실행 환경

- Java: Oracle JDK 21.0.2, Maven Wrapper: 3.9.11.
- OS: macOS 26.4.1 / aarch64. Docker Engine 27.4.0.
- Spring Boot 4.1.1 / Spring Modulith 2.1.1 / Testcontainers 2.0.5.
- 실제 DB: Testcontainers `postgres:16`, 실행 서버 PostgreSQL **16.15**. Compose/운영 DB에 테스트 데이터를 쓰지 않았다.
- JPA: Hibernate 7.4.5.Final (이번 실행 로그), `ddl-auto=validate`, `open-in-view=false` 유지.
- 최초 Company/Site 이외 업무 Entity/table 없음. Flyway 적용 1개 / pending 0 / validate 성공.

`backend/`에서 JDK 21을 선택한 후 실행:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp verify
```

[실행 발췌 및 SHA-256 목록](organization-org-01-run.txt)에 최종 성공 실행 로그와 backend Java/SQL/pom/config의 개별 파일 경로·SHA-256 목록이 있다. 해당 목록은 실행 입력 파일을 식별하기 위한 근거다.

## 2. 실제 테스트 집계

| 실행기 | 테스트 클래스 | Tests | Failures | Errors | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| surefire | `com.kflow.erp.LayerArchitectureTest` | 3 | 0 | 0 | 0 |
| surefire | `com.kflow.erp.ModuleArchitectureTest` | 1 | 0 | 0 | 0 |
| surefire | `com.kflow.erp.organization.OrganizationContractTest` | 2 | 0 | 0 | 0 |
| surefire | `com.kflow.erp.organization.OrganizationValuesTest` | 34 | 0 | 0 | 0 |
| surefire | `com.kflow.erp.technical.web.FrameworkProblemHandlerTest` | 3 | 0 | 0 | 0 |
| failsafe | `com.kflow.erp.FoundationIT` | 2 | 0 | 0 | 0 |
| failsafe | `com.kflow.erp.organization.OrganizationIT` | 22 | 0 | 0 | 0 |

- Surefire: **43**, Failsafe: **24**, 합계 **67**. 실패 0 / 오류 0 / skip 0. `BUILD SUCCESS`.
- ORG-01 신규: 순수 값 규칙 34 + 계약 2 + PostgreSQL IT 22 = 58건. 기존 Foundation/경계/오류 회귀 9건 유지.
- `OrganizationIT`의 parameterized company/site/slot case는 실제 발견된 실행 건수에 포함된다.
- 원본 보고서: 로컬 `backend/target/surefire-reports/TEST-*.xml`, `backend/target/failsafe-reports/TEST-*.xml`, 각 `.txt`, `failsafe-summary.xml`. Maven 생성 파일이며 GitHub에 업로드된 것으로 표시하지 않는다.
- Modulith 14개 모듈 감지 + `ApplicationModules.verify()` 통과. ArchUnit 3개 규칙 유지·통과.
- FoundationIT의 PostgreSQL 제품/major 16·컨테이너 연결·JPA validate·Flyway·빈 OpenAPI 업무 paths·Swagger 검증 유지. 이전 table/Entity 0개 기대만 Company/Site 정확히 2개로 갱신.

## 3. 필수 ID → 실제 검증 매핑

아래 메서드는 별도 표기가 없으면 `OrganizationIT`다. 모든 행은 최종 verify에서 PASS다.

| 필수 ID | 실제 테스트 / case | 확인 결과 |
| --- | --- | --- |
| ORG-T01-A | `migrationSeedAndRepeatedStartupPreserveCompany`, `FoundationIT.bootsOnPostgresWithOnlyOrg01BusinessSchema` | 빈 Testcontainer DB에서 V1, 고정 UUID v4·HANGYEOL·slot 1·version 0·동일 초기 시각·Site 0 |
| ORG-T01-B | `migrationSeedAndRepeatedStartupPreserveCompany` | migrate 재호출 적용 0건, 별도 앱 context 재기동 후 Company 전체 필드 동일 |
| ORG-T01-C | `secondCompanyAndSingletonBypassRollback[1]` | 두 번째 Company UNIQUE 거부, 같은 트랜잭션의 선행 이름 변경도 rollback |
| ORG-T01-D | `secondCompanyAndSingletonBypassRollback[2,NULL]`, `databaseRequiredFieldsAndChecksRollback[company]` | slot CHECK/NOT NULL 거부, 원본 유지 |
| ORG-T01-G | `missingCompanyFailsRuntimeAndStartupWithoutReseeding` | 실제 DB에서 Company 삭제 fixture, 조회/등록/목록과 새 앱 기동 실패, Company/Site 0개 유지 |
| ORG-T01-H | `renamedCompanySurvivesRestartAndRead` | 정정 이름·ID·code·createdAt·version 1 유지 |
| ORG-T01-I | `alternativeValidCompanyFixtureWorksWithoutSeedConstants` | 격리 DB의 다른 UUID v4/ALTERNATE 법인으로 실제 앱 재기동·조회·Site 등록 성공 |
| O1-T01 | `canonicalCodesInactiveDuplicateNamesAndNamespaces`, `OrganizationValuesTest.canonicalCode` | hq/Hq/HQ/SPACE 정규화 및 중복 충돌 |
| O1-T02 | `OrganizationValuesTest.invalidCodes/codeLengthBoundary`, `invalidInputsDoNotPersistAndUnicodeBoundaryPersists`, `databaseRequiredFieldsAndChecksRollback[company,site]` | 32/33, Unicode/전각/제어 공백 거부 및 DB 소문자 우회 거부 |
| O1-T03 | `concurrentCanonicalCodeRegistrationCommitsExactlyOne` | 독립 실제 트랜잭션 2개가 Company 조회 후 latch에서 만남, 성공 1·SITE_CODE_CONFLICT 1·DB HQ 1행 |
| O1-T04 | `canonicalCodesInactiveDuplicateNamesAndNamespaces` | SQL 테스트 fixture INACTIVE도 조회되고 동일 코드 신규 등록 거부 |
| O1-T05 | 같은 메서드 | 동일 표시명·다른 code는 다른 UUID로 저장 |
| O1-T06 | 같은 메서드 | 저장된 Company code와 같은 Site code 허용 |
| O1-T07 | `databaseRequiredFieldsAndChecksRollback[company,site]`, `referencingSiteRestrictsCompanyDeleteAndIdChange`, `canonicalCodesInactiveDuplicateNamesAndNamespaces` | 실제 FK/NOT NULL/CHECK/PK/RESTRICT와 ACTIVE 초기 저장 |
| O1-T08 | `siteRenameStableIdentityAndNoopWithoutWrite` | ID/companyId/code/status/createdAt 동일, 새 name/updatedAt, version 0→1 |
| O1-T09 | `concurrentRenamesHaveOneWinnerAndRollbackLoser[company,site]` | 같은 version을 읽은 두 독립 영속성 context, 성공 1·VERSION_CONFLICT 1, 최종 DB는 승자의 이름·version 1 |
| O1-T10 | `companyNoopWithoutWriteAndStaleRejected`, `siteRenameStableIdentityAndNoopWithoutWrite`, `noopChecksVersionAtCommitAndRollsBackConcurrentStaleRead[company,site]` | stale/no-op 검사, xmin·version·updatedAt 유지, 경쟁 commit 후 no-op 완료 시 VERSION_CONFLICT |
| O1-T11 | `migrationSeedAndRepeatedStartupPreserveCompany`, `renamedCompanySurvivesRestartAndRead`, `canonicalCodesInactiveDuplicateNamesAndNamespaces`, no-op 두 메서드 | 조회/재기동/중복 실패가 원본 version/시각을 바꾸지 않음 |
| O1-T12 | `missingEmptyInvalidCompanyAndDatabaseFailureAreDistinct`, `missingCompanyFailsRuntimeAndStartupWithoutReseeding` | Optional.empty / 정상 빈 목록 / COMPANY_NOT_FOUND / COMPANY_CONFIGURATION_INVALID / 실제 SQL 장애를 구분 |
| O1-T13 | `internalPaginationFiltersAndBoundaries` | 22행, 기본 20+2·hasNext, code ASC, 상태별 필터, 크기 1/100 허용·0/101 거부·음수 페이지 거부 |
| O1-T14 | `OrganizationContractTest.noIdentityCodeStatusOrCompanyMutationCommands`, 이름 변경 실제 DB 테스트 | 등록 입력 code/name만, 명령은 등록/이름 변경만, 식별자/code/companyId setter 및 변경 명령 없음·JPA updatable=false |
| O1-T15 | `OrganizationContractTest.publishedPortOnlyOffersTwoSingleRecordContracts`, `ModuleArchitectureTest`, `LayerArchitectureTest` | 단건 2계약/불변 최소 DTO, 내부 SitePage와 목록 비공개, 모듈/계층 경계 |

## 4. 실제 DB 관찰 및 실패 검증 방식

- Migration/restart: Company `c04b2a3e-6c22-43a7-84f1-d7df625cb826`, code HANGYEOL, slot 1, version 0, Site 0. 초기 createdAt/updatedAt 동일하며 반복 기동 후 전체 row 비교 일치.
- 직접 DB 쓰기: 먼저 이름을 `rollback`/`롤백 대상`으로 바꾼 같은 트랜잭션에서 제약 위반을 발생시켰다. 예외 후 별도 JDBC 조회로 전체 원본 row 일치 확인. 단순 예외 검사나 JPA 1차 캐시만의 비교가 아니다.
- 코드 경쟁: 두 쓰기의 실제 Company 읽기가 끝난 후 latch를 풀었다. 동일 HQ 성공 1 / SITE_CODE_CONFLICT 1, commit 후 HQ count 1/version 0. 사전 중복 조회는 사용하지 않고 DB UNIQUE가 최종 방어한다.
- 이름 경쟁: Company 최종 이름 `승자A`, Site 최종 이름 `승자B`, 각각 version 1. 반대 요청은 VERSION_CONFLICT. 승자 순서는 고정 계약이 아니며 결과에서 성공한 DTO와 DB 이름을 대조했다.
- 정상 no-op: Site xmin `769`, Company xmin `876`이 요청 전후 동일. 전체 row도 동일하여 version/updatedAt만 같게 보이는 쓰기를 성공으로 오인하지 않았다. xmin 값은 이 실행의 테스트 DB 관찰값이다.
- 경쟁 no-op: no-op 트랜잭션이 실제 `OPTIMISTIC` lock을 요청한 후 latch로 정지했다. 다른 독립 트랜잭션의 이름 변경을 commit한 다음 풀었다. Company/Site 모두 no-op 완료에서 VERSION_CONFLICT, DB는 `경쟁 변경`/version 1 유지.
- Spy는 동시 실행의 rendezvous에만 사용하며 실제 JPA 조회/쓰기/lock/flush/commit을 호출한다. Repository 응답을 가짜 성공으로 대체하거나 sleep으로 경쟁을 추정하지 않는다.
- DB 조회 장애: 격리 테스트 트랜잭션 안에서 실제 테이블 이름을 임시 변경해 SQL 조회 실패를 만들고 rollback했다. 단건/목록 모두 Spring DataAccessException이며 Optional.empty/빈 목록/업무 NotFound로 바뀌지 않음.
- 법인 누락: Flyway 이력은 남고 Company 0행인 fixture에서 새 앱 기동이 COMPANY_CONFIGURATION_INVALID로 실패했다. ApplicationReady에 도달하는 정상 기동으로 처리하지 않으며 자동 seed 없음.
- 다른 seed fixture: 정상 singleton 제약을 만족하는 UUID/code를 SQL로 변경한 **테스트 데이터**로 앱을 다시 띄워 읽기/등록 확인. 운영 ID/code 변경 명령은 만들지 않음.
- 복수 법인 계약은 **별도 선택 계약 검증** `multipleCompanyContractRejectsInsteadOfChoosingFirst`로 저장 Port가 2개를 반환할 때의 실패를 확인했다. 이는 mock 응답을 쓰는 Application 계약 테스트이며, singleton 제약을 해제한 PostgreSQL 손상 DB 검증은 아니다. 정상 DB 최대 1개는 실제 제약 테스트로 검증했다.

## 5. 구현 선택과 실제 발견한 문제

- 입력 code는 ASCII SPACE만 제거한 뒤 허용 문법을 확인하고 대문자로 바꾼다. 이름은 Unicode code point 길이로 검증한다. JPA Entity는 Organization 소유이며 타 모듈 참조/SQL은 추가하지 않았다.
- Company는 2행까지 조회해 정확히 1행인지 확인한다. 별도 COUNT나 seed 값 whitelist, 누락 시 복구는 없다. Startup ApplicationRunner의 실패는 정상 준비 완료를 막는다.
- UUID를 미리 부여한 Site는 `persist`하고 nullable `@Version Long`을 provider가 초기화한다. 실제 최초 version 0을 DB에서 확인했다.
- 단순 사전 version 비교만으로 끝내지 않는다. 변경은 JPA version 조건, no-op는 OPTIMISTIC 완료 검증을 사용한다. commit 예외까지 Application 트랜잭션 경계 밖에서 VERSION_CONFLICT로 변환한다. JPA의 지연 검증은 [Jakarta Persistence 3.2 §3.5](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html)를 참고했고, 실제 provider 동작은 위 DB 테스트로 확인했다.
- **개발 중 관찰한 실패:** 최초 신규 전체 verify에서 `missingCompanyFailsRuntimeAndStartupWithoutReseeding`의 예외 검증 1건이 실패했다. root cause로 OrganizationFailure가 감싸질 것으로 예상했지만 예외 자체가 직접 전달된 것으로 관찰했다. 현재 검토 자료에는 당시 raw 실패 로그가 보존되어 있지 않으므로, 이 설명은 보존된 실행 증거가 아닌 개발 중 관찰 기록이다.
- 관찰에 따른 수정: 기동 실패 동작을 바꾸거나 검증을 삭제하지 않고 예외 자체의 타입과 code를 검사하도록 테스트를 수정했다. 과거 실패 로그를 재구성하지 않는다. 수정 후 동일 시나리오의 PASS는 보존된 최종 verify 결과와 위 테스트 매핑을 근거로 한다.
- 기존 Foundation/경계/오류 회귀 9건의 PASS는 보존된 최종 verify 집계에 포함된다. 별도 초기 실행의 성공 근거로 확대하지 않는다.

## 6. 한계와 미실행

- 원격 GitHub Actions: **NOT_RUN**. 검증 commit/run/job URL 없음. 과거 Foundation CI를 이번 ORG-01 성공으로 재사용하지 않음.
- ORG-T01-E Flyway 동시 최초기동: 선택 / NOT_RUN.
- ORG-T01-F Migration 중간 실패주입: 선택 / NOT_RUN. Migration은 PostgreSQL transactional DDL로 구성했지만 이 선택 실패주입을 실행했다고 주장하지 않음.
- 복수 Company 손상 PostgreSQL schema: 선택 / NOT_RUN. 정상 제약을 해제하는 운영 플래그 없음.
- cross-module 외부 트랜잭션을 소유하는 미래 명령은 이번 검증 범위가 아니다. 현재 TransactionTemplate은 REQUIRED로 참여하며, 외부 트랜잭션이 있다면 최종 commit/실패 처리는 그 소유자가 담당한다. REQUIRES_NEW로 ERP 원자성을 임의 분리하지 않았다.
- API/JWT/IAM/HR/ORG-02/03/Site 상태 전이/Audit/Scope/Frontend는 구현하지 않았다. 표시 시각을 필수 감사 완료로 표시하지 않는다.
- 성능 수치는 측정하지 않았다. 위 테스트 시간은 빌드 실행 기록일 뿐 업무 성능 지표가 아니다.

## 7. 변경 파일

- `backend/src/main/java/com/kflow/erp/organization/api/`: CompanyReference, SiteReference, OrganizationReferenceQueries, OrganizationFailure.
- `backend/src/main/java/com/kflow/erp/organization/application/`: OrganizationService, OrganizationStartupCheck, OrganizationStore, SiteListQueries, SiteListItem, SitePage.
- `backend/src/main/java/com/kflow/erp/organization/domain/`: Company, Site, OrganizationValues.
- `backend/src/main/java/com/kflow/erp/organization/infrastructure/JpaOrganizationStore.java`.
- `backend/src/main/resources/db/migration/V1__organization_company_site.sql`.
- `backend/src/test/java/com/kflow/erp/organization/`: OrganizationValuesTest, OrganizationContractTest, OrganizationIT.
- `backend/src/test/java/com/kflow/erp/FoundationIT.java`, `backend/README.md`.
- `PROJECT-INDEX.md`, `docs/plans/organization-hr-iam-implementation-plan-v0.1.md`, `docs/specs/organization-hr-iam-detailed-design-v0.1.md`, `docs/specs/organization-org-01-company-site-detailed-design-v0.1.md`.
- 본 Evidence와 `organization-org-01-run.txt`.
