# Organization HR Core IAM Core Authentication Authorization Implementation Plan v0.1

- 상태: **PLAN DRAFT — ORG-01 LOCAL VERIFIED / 나머지 Slice PLANNED / 원격 CI NOT_RUN**
- 작성일: 2026-10-07 (Asia/Seoul)
- 대상 경로: `docs/plans/organization-hr-iam-implementation-plan-v0.1.md`
- 기준 브랜치/커밋: `codex/backend-foundation` / `88347ded6d941c497ccefaf1d8447ce28d196d95`
- ORG-01 채택 반영일: 2026-10-07 (Asia/Seoul). [ORG-01 상세설계 v0.1](../specs/organization-org-01-company-site-detailed-design-v0.1.md)의 O1-D01~10만 ADOPTED로 확정
- 정책 근거: [ADR 0001 신규 초안](../adr/0001-organization-employee-account-boundaries.md)의 사용자 채택 E-01/E-02
- 설계 입력: [Organization + HR Core + IAM 상세설계 v0.1](../specs/organization-hr-iam-detailed-design-v0.1.md)
- 기반 구현 근거: [Backend Foundation Test Evidence](../test-evidence/backend-foundation.md)

## 1. 목표와 현재 기준

구현 순서는 **Organization → HR Core → IAM Core → Authentication → Authorization**이다. Authentication과 Authorization은 IAM 책임의 구현 단계이며, 별도 업무 모듈이나 서버를 새로 추가한다는 뜻이 아니다.

한 Slice는 기능 하나에 필요한 모델·저장·Application Use Case·Published Port·검증을 묶는다. 테이블 전체, Repository 전체, Controller 전체를 각각 한 단계로 만드는 방식은 사용하지 않는다. 초기 Core Slice의 종료점은 내부 Use Case와 실제 DB 검증이다. 외부 업무 API는 Authentication·Authorization 조건을 충족한 뒤 연결한다.

확인한 커밋에는 Java 21 / Spring Boot 4.1.1 / Spring Modulith 2.1.1 / PostgreSQL용 Foundation, 14개 모듈 metadata, 경계 테스트가 있다. Organization/HR/IAM 업무 클래스·Migration과 Spring Security 의존성은 아직 없다. 저장소의 기존 Foundation 검증 결과는 업무 기능의 구현·검증 완료를 뜻하지 않는다.

상위 상세설계의 파일 추가 커밋은 1459447이며, 문서 안의 1e9e2c6은 작성 당시 Foundation 기준이다. ADR/이 계획·ERD E-01/E-02 CLOSED 동기화는 88347de에 반영돼 있다. 이번 ORG-01 후속 채택 반영의 읽기 기준은 88347de다. Foundation의 과거 실행 근거를 새 업무 테스트 결과로 재사용하지 않는다.

## 2. 준비와 공통 종료 조건

### 2.1 문서 준비

기준 커밋에는 신규 ADR 초안과 이 계획, ERD의 E-01/E-02 CLOSED 동기화가 반영돼 있다. ADR을 기존 원본의 복원본으로 기록하지 않는다. ORG-01 구현 전에는 사용자 채택 상세설계와 관련 문서 참조를 같은 브랜치에 반영한다. E-03~E-15는 기존 상태를 유지한다.

이미 채택된 E-01/E-02와 ORG-01 O1-D01~10은 다시 승인 대기로 돌리지 않는다. ORG-01 밖의 DESIGN v0.1 후보·OPEN 계약을 이번 채택으로 확정하지 않는다. GitHub 문서 변경안 및 Astra 프롬프트 준비는 실제 구현 실행이나 완료를 뜻하지 않는다.

### 2.2 모든 Slice의 공통 기준

- 하나의 Slice마다 관찰 가능한 Use Case와 성공·실패 결과를 명시한다. 아래의 테스트 ID는 상세설계 9절의 미실행 명세를 참조한다.
- Migration이 필요한 Slice는 빈 PostgreSQL DB와 직전 Slice의 schema에서 적용·Flyway validate를 확인한다. 업무 schema를 H2로 대체하거나 테스트를 skip해서 완료 처리하지 않는다.
- 변경하는 원본은 소유 모듈에서만 쓴다. 교차 모듈 JPA Entity/Repository, 타 모듈 테이블 직접 SQL 접근, 순환 의존을 추가하지 않는다.
- DB 제약과 Application 검증을 구분한다. FK만으로 ACTIVE·재직 상태를 보장하거나 EXCLUDE만으로 소속 공백까지 막았다고 설명하지 않는다.
- 변경 실패의 전체 rollback과 중복·경쟁 요청을 실제 트랜잭션으로 확인한다. version 필드의 존재만으로 충돌 제어가 완료됐다고 보지 않는다.
- 기존 ModuleArchitectureTest, LayerArchitectureTest와 관련 Foundation 검증을 유지한다. 업무 테스트는 해당 Slice에서 새 근거를 기록한다.
- 권한 검증이 준비되기 전 업무 HTTP 경로를 공개하지 않는다. permitAll, 임의 관리자 Actor, 브라우저 선택 사용자 또는 고정 Role을 임시 인증으로 넣지 않는다. 테스트 fixture는 테스트 전용이다.
- API 계약은 해당 Slice와 관련된 필드·오류·재시도만 확정한다. generic Idempotency 플랫폼이나 미정 Permission/Scope 저장소를 선행 구현하지 않는다.
- ORG-01은 [Test Evidence](../test-evidence/organization-org-01.md)의 로컬 실행 결과를 따른다. 나머지 Slice의 테스트는 예정이며 미실행이다.

## 3. 작은 Slice별 순서

| 순서 | Slice | 관찰 가능한 종료점 | 직접 선행 |
| --- | --- | --- | --- |
| 1 | ORG-01 회사와 사업장 | 법인 1개와 독립 Site를 저장·조회할 수 있음 | 문서 준비, 해당 저장 계약 |
| 2 | ORG-02 부서와 직급 | Department/Site 독립·회사 공통 Position을 조회할 수 있음 | ORG-01 |
| 3 | ORG-03 상태와 배정 Guard | 비활성 기준의 신규 배정을 막고 기존 참조를 읽을 수 있음 | ORG-02, 조직 비활성/Guard 계약 |
| 4 | HR-01 직원과 최초 소속 | 계정 없이 Employee와 최초 Assignment를 원자적으로 등록 | ORG-03, 인사 기간 계약 |
| 5 | HR-02 현재와 이력 조회 | 명시 기준일에 유효 소속 정확히 1개를 찾음 | HR-01 |
| 6 | HR-03 전보와 승진 | 한 명령으로 이전 구간 종료·새 구간 생성, 실패 시 원복 | HR-02 |
| 7 | HR-04 퇴사 | Employee 재직 종료와 Assignment 종료가 함께 반영됨 | HR-03, 퇴사 효력 계약 |
| 8 | IAM-01 직원 연결 계정 | 직원당 계정 최대 1개, 퇴사와 계정 생성 경쟁 검증 | HR-04 |
| 9 | IAM-02 역할 기준 | Position과 독립된 Role을 관리·조회 | IAM-01, 역할 lifecycle 계약 |
| 10 | IAM-03 부여와 철회 | UserRole 부여 건을 식별하고 grantId로 철회·재부여 | IAM-02 |
| 11 | AN-01 Credential과 bootstrap | 선택한 인증 수단의 안전한 계정 준비·초기 관리자 경로 | IAM-03, 인증/운영 선행 결정 |
| 12 | AN-02 로그인과 로그아웃 | 서버가 로그인 주체를 검증하고 로그아웃을 처리 | AN-01, 세션·휴직 계약 |
| 13 | AN-03 요청별 접근 신원 | 퇴사/비활성화 후 기존 세션의 새 요청을 차단 | AN-02 |
| 14 | AZ-01 행위 권한 | 유효 Role의 Allow 합집합으로 해당 행위 허용을 판정 | AN-03, 해당 행위 매핑 결정 |
| 15 | AZ-02 대상 데이터와 첫 API | 선택 도메인의 대상 접근 규칙으로 첫 업무 API를 보호 | AZ-01, 해당 도메인 Scope 결정 |
| 16 | AZ-03 권한 관리 API | 부여 가능 Role·자기 권한·관리자 보호 계약에 맞게 관리 API 보호 | AZ-02, 관리자 운영 결정 |

표의 종료점은 계획상의 기준이다. 선행 Slice가 완료돼도 미결 정책이 필요한 기능은 바로 구현 준비 완료로 바뀌지 않는다.

### 3.1 Organization

#### ORG-01 회사와 사업장

- 설계 상태: [ORG-01 상세설계 v0.1](../specs/organization-org-01-company-site-detailed-design-v0.1.md)의 O1-D01~10 전부 ADOPTED. 2026-10-07 ORG-01만 내부 구현·로컬 필수 검증 완료. [실행 근거](../test-evidence/organization-org-01.md). 원격 반영/CI는 미실행이다.
- 범위: Company 1개 Flyway 초기화, Site 등록·단건·내부 목록 조회, Company/Site 표시명 수정. Company 상태·일반 생성/삭제, Site 활성/비활성화 명령은 포함하지 않는다.
- 초기화 계약: Flyway seed + DB singleton + Runtime의 Company 정확히 1건 존재 검증. Application은 고정 seed UUID/code를 magic constant로 복제하거나 일치 검증하지 않는다.
- 저장 계약: UUID v4 Stable ID, BIGINT optimistic version, Company/Site canonical code와 불변 코드, DB-01/02. Site는 Company별 모든 상태에서 코드가 유일하고 초기 ACTIVE다.
- 공개 계약: OrganizationReferenceQueries의 getCompany·findSite 최소 단건 2계약만 Published. Site 목록·pagination/sorting 및 SitePage는 Organization 내부 계약이며 JPA Entity를 노출하지 않는다.
- API 후보: GET company, GET/POST sites. 초기에는 외부 Controller를 활성화하지 않고 Application/Port/DB 테스트로 확인한다.
- 선행 결정: ORG-01에 필요한 Company/Site 코드·초기화·Stable ID/version·DB-01/02는 채택 완료다. 다른 Entity 코드, Guard, Data Scope, 휴직 로그인은 후속 상태를 유지한다.
- 필수 종료 검증: 상세설계 10절의 ORG-T01-A/B/C/D/G/H/I 및 10.1절의 O1-T01~15. PostgreSQL 16/Testcontainers와 기존 Modulith/ArchUnit/CI 검증을 유지하고 별도 ORG-01 Test Evidence를 기록한다.
- 선택 검증: Flyway 동시 최초기동(ORG-T01-E)·Migration 실패주입(ORG-T01-F)은 Foundation/운영 선택 항목이다. 미실행을 ORG-01 미완료로 처리하지 않는다. Site 코드 동시 등록과 version 충돌은 필수다.
- 실행 경계: 이번 문서 반영과 프롬프트 작성으로 Astra를 실행하거나 ORG-02/03·Employee·IAM·인증/인가·Data Scope·Frontend를 시작하지 않는다.

#### ORG-02 부서와 직급

- 범위: Department/Position 등록·기본 표시정보 수정·조회. 조직 계층·부서장·직책·결재선은 제외한다.
- 저장 후보: 각 Company 참조와 코드 UNIQUE. Department.siteId 또는 Position.departmentId 종속 관계를 만들지 않는다.
- 공개 계약: OrganizationReferenceQueries의 Department/Position 확장. 조회 목적에 맞는 최소 DTO.
- API 후보: departments/positions의 GET/POST/PATCH. 외부 공개 조건은 AZ-02다.
- 선행 결정: ORG-01과 해당 코드·변경·version 계약.
- 종료 검증: 서로 다른 Site와 독립적으로 부서를 참조 가능, 회사 공통 Position, 중복/누락 참조 거부. ORG-T02의 두 직원 배정 검증은 HR-01/02에서 완성한다. 직급 변경에 따른 역할 영향 검증은 IAM 이후에 완성한다.

#### ORG-03 상태와 배정 Guard

- 범위: Site/Department/Position 상태 변경, 비활성 대상의 이력 조회, 신규 배정 허용 검사. 직원 이동·퇴사·계정 차단을 자동 전파하지 않는다.
- 공개 계약: OrganizationAssignmentGuard와 OrganizationReferenceQueries의 조회 목적 구분.
- 저장/동시성 후보: 상태·변경 사유·예상 version. Guard 확인과 이후 저장의 안정성을 위한 기술 계약을 먼저 정한다. Lock 강도/순서를 이 계획에서 확정하지 않는다.
- API 후보: activate/deactivate 명령. 사용 중 기준의 비활성화 운영 규칙이 정해지기 전 공개 완료로 표시하지 않는다.
- 선행 결정: OHI-03의 사용 중 기준 처리 범위, OHI-09의 조직 Guard·동시 상태 변경 계약.
- 종료 검증: ORG-T03과 비활성 신규 선택 거부·이력 식별 유지·낡은 version 충돌. 실제 HR 배정과 비활성화 경쟁인 HR-T10은 HR-03 종료에도 다시 확인한다.

### 3.2 HR Core

#### HR-01 직원과 최초 소속

- 범위: Employee와 최초 EmployeeAssignment를 한 HR 업무 단위로 등록한다. User 자동 생성은 포함하지 않는다.
- 공개 계약: EmployeeIdentityQueries 및 EmployeeAccountLinkGuard의 기본 HR 사실·존재 조회. 계정 수 제약의 소유자는 IAM이다.
- 저장 후보: 사번 UNIQUE, 완전한 배정 참조, 유효 기간·재직 경계, 동일 Company 확인, 기간 중복 제약 후보. 교차 FK와 확장 사용은 검토 후 Migration에 반영한다.
- API 후보: POST /hr/employees. 초기에는 내부 Use Case/Port 검증이며 공개는 AZ-02다.
- 선행 결정: OHI-01의 최초 입력·효력일·초기 이관 범위, OHI-08의 사번 규칙, OHI-09의 기간/Guard 제약. 당일 등록만 지원한다는 추천도 추가 채택 전에는 정책으로 사용하지 않는다.
- 종료 검증: HR-T01/02/05, IAM-T01, ORG-T02. 최초 Assignment 실패 시 Employee도 미저장, 겹치는 기간·불완전 참조 거부, 같은 부서의 두 직원을 다른 Site에 배정 가능.

#### HR-02 현재와 이력 조회

- 범위: Employee 기본정보, 기간별 Assignment 조회, 기준일에 유효한 현재 소속. 조회 DTO에서 현재 ID를 결합한다.
- 공개 계약: EmployeeAssignmentQueries. 현재 신원 조회의 기준일은 서버가 정하며, 권한 있는 이력 조회의 asOf와 구분한다.
- 모델 후보: Assignment를 현재/과거의 단일 저장 원본으로 삼는 상세설계안과 [from, to) 경계를 검토한다. effectiveTo가 NULL이라는 이유만으로 현재를 판정하지 않는다.
- API 후보: GET employees/{id}, GET assignments. 과거 이름 Snapshot을 복원하는 API라고 표시하지 않는다.
- 선행 결정: HR-01의 기간 계약, 한 요청의 업무 날짜/Clock·조회 오류 계약.
- 종료 검증: HR-T03/04/08/09/11. 경계일 정확히 1개, 미래 fixture와 현재 구분, 재직 밖 소속 거부, 0개/복수 현재 소속을 임의로 선택하지 않음. 정상 인사 입력의 지원 범위와 미래 조회 fixture는 구분한다.

#### HR-03 전보와 승진

- 범위: 이전 Assignment 종료 + 새 Assignment + Employee 변경 version을 하나의 HR 트랜잭션에서 반영한다. 임의 Assignment CRUD는 제공하지 않는다.
- 공개 계약: ORG-03의 배정 Guard 사용, HR 현재/이력 조회로 결과 확인.
- API 후보: POST assignment-changes. 효력일·expectedVersion·재시도 조회 계약은 해당 Slice에서 확정한다.
- 선행 결정: OHI-01의 허용 효력일 범위와 같은 날 재변경·정정 처리, OHI-09의 Employee 변경 직렬화/낙관적 충돌 및 조직 Guard 계약. 기간 표현이 있다는 이유로 예약·소급 변경을 허용하지 않는다.
- 종료 검증: HR-T02/03/04/06/07/10. 중간 실패 전체 rollback, 두 전보 경쟁 후 1개 현재 소속, 중복·공백 없음, 비활성화와 배정의 결과가 합의한 커밋 순서 계약과 일치.

#### HR-04 퇴사

- 범위: Employee 재직 종료 + 마지막 Assignment 종료. 직원·이력 행을 보존한다. User 생성/비활성화를 HR에서 직접 호출하지 않는다.
- 공개 계약: EmployeeIdentityQueries에 퇴사 효력 사실을 제공하고 EmployeeAccountLinkGuard가 계정 연결 가능 상태를 일관되게 확인하도록 준비한다.
- API 후보: POST terminate. 재입사·퇴사 취소/오입력 정정은 OHI-04 후속이다.
- 선행 결정: 퇴사 효력일 용어·날짜와 처리 시점, 전보/퇴사 경쟁, 퇴사 명령의 재시도 계약(OHI-01/09).
- 종료 검증: HR-T09 및 퇴사 중간 실패 원복, 계정 없는 직원 퇴사, 전보와 퇴사 경쟁, 기존 참조 보존. 당일 입사·당일 퇴사 등 빈 재직 구간도 해당 계약에서 허용/거부를 명시한다.
- 후속 검증: 계정 생성 경쟁은 IAM-01, 신규 로그인·기존 세션 차단은 AN-02/03이다. HR-04만으로 IAM-T10의 실제 인증 검증을 PASS 처리하지 않는다.
- 휴직 시작/복귀 및 휴직 로그인 영향은 OHI-02 OPEN이며 이 Slice에 끼워 넣지 않는다.

### 3.3 IAM Core

#### IAM-01 직원 연결 계정

- 범위: Employee에 연결된 User 등록·조회·관리 상태 변경. Credential·로그인·권한 판정은 이후 단계다.
- 저장 후보: employeeId 필수·UNIQUE, loginKey UNIQUE, Company 일치·FK, 계정 상태·version·변경 근거. 비활성 계정의 직원 연결을 해제해 새 계정을 만들지 않는다.
- 공개 계약: HR의 EmployeeAccountLinkGuard와 EmployeeIdentityQueries 사용. PrincipalAccessQueries의 최소 User/Employee 식별 기반을 준비한다.
- API 후보: POST users, GET users, enable/disable. 외부 관리 API 공개는 AZ-03 이후다.
- 선행 결정: OHI-08의 loginKey, OHI-05/06의 초기 상태·활성화 범위, OHI-09의 계정 생성/퇴사 Guard. DISABLED 초기값은 상세설계 추천이며 이 계획의 새 채택 결정이 아니다.
- 종료 검증: IAM-T01/02/03/04/12. 직원당 동시 계정 생성 최대 1개, 없는/다른 회사/퇴사 직원의 생성 거부, 비활성 후 두 번째 계정 금지, 퇴사와 생성 경쟁.
- 보안 제한: 휴직 연결/접근 정책이 필요하면 OHI-02를 선행 결정으로 추가한다. ON_LEAVE를 TERMINATED처럼 처리하지 않는다.

#### IAM-02 역할 기준

- 범위: Role 식별·정의 조회·표시정보 변경 및 합의된 상태 lifecycle. Position에 Role을 자동 연결하지 않는다.
- 저장 후보: 회사별 코드 UNIQUE, 상태·version. Permission/Scope 테이블이나 전사 권한표를 추가하지 않는다.
- 공개 계약: IAM 내부 역할 조회. 필요한 최소 역할 사실만 다음 Slice로 제공한다.
- API 후보: roles GET/POST/PATCH와 상태 변경. 외부 공개는 AZ-03 이후다.
- 선행 결정: OHI-05의 Role 상태 전이·재활성화, OHI-08의 역할 코드. 초기 Role 목록·행위 매핑을 임의로 ADMIN 등으로 고정하지 않는다.
- 종료 검증: 중복/없는 회사/낡은 version 거부, Role과 Position 독립. UserRole 일괄 철회가 포함된 비활성화 후보는 IAM-03에서 원자성 검증을 완성한다.

#### IAM-03 역할 부여와 철회

- 범위: UserRole을 명시적 부여 건으로 생성, 부여 이력 조회, grantId별 철회, 재부여. 같은 User/Role의 미철회 중복을 막는다.
- 저장 후보: 미철회 partial UNIQUE, 시각/행위자 짝·FK. 철회 행 보존과 재부여 새 행 모델을 검토한다.
- API 후보: role-grants POST/GET, 특정 grantId revoke. 권한 있는 실제 요청자를 받는 외부 경로는 AZ-03에서 공개한다.
- 선행 결정: OHI-05의 부여/철회·역할 비활성화 정책, OHI-09의 동시 부여/상태 변경, 초기 actor 기록과 bootstrap 계약. 실제 행위 권한은 AZ-01/03 결정과 구분한다.
- 종료 검증: IAM-T06/07/08/09. 중복 부여·비활성 Role 부여 거부, A 철회 후 B 재부여에 지연된 A 철회가 영향 없음, 합의한 lifecycle의 비활성화/철회 원자성. Position 변경만으로 역할 자동 부여·권한 상승 없음.
- 종료의 의미: 계정/역할 저장 Use Case 완성이다. 로그인 또는 업무 인가 완료로 표시하지 않는다.

### 3.4 Authentication

#### AN-01 Credential과 초기 관리자 준비

- 범위: 선택된 인증 수단에 맞춘 Credential 준비·관리 계정 초기 설정 경로. 일반 계정 없이 관리자 bypass를 넣지 않는다.
- 선행 결정: OHI-06의 Session/JWT/SSO 선택, Credential·초대/비밀번호/잠금·복구·철회·초기 관리자 절차. OHI-05의 초기 관리자 Role/행위 매핑·부여 권한과 초기 기록 actor도 함께 정한다.
- 경계: 사람 관리자도 Employee↔User 연결 정책을 따른다. Credential을 HR/Organization에 넣거나 API/로그/Published DTO로 노출하지 않는다.
- 기술 계약: 선택 결과에 맞는 의존성·어댑터·보안 테스트를 설계한다. 인증 수단에 따른 CSRF/CORS/Cookie/Token 등 적용 조건을 그때 명시한다.
- 종료 검증: bootstrap 중간 실패·재시도·중복 실행·사전 권한 획득 시도·Credential 누출 검증(SEC-T03 확장). 선택된 인증 방식의 실제 실패/복구 시나리오를 별도 테스트 ID로 기록한다.
- 현재 상태: OHI-06은 OPEN이므로 특정 인증 기술이나 초기 비밀번호를 이 계획에서 지정하지 않는다.

#### AN-02 로그인과 로그아웃

- 범위: 선택된 인증 수단으로 주체를 확인하고 로그인·로그아웃 및 필요한 인증 상태를 처리한다. 로그인 성공은 업무 행위 권한 획득이 아니다.
- 공개 계약: 서버가 얻은 userId에서 PrincipalAccessQueries로 User/Employee 상태를 확인한다. 브라우저가 보낸 userId·Role·소속을 신뢰 근거로 사용하지 않는다.
- API 후보: 로그인/로그아웃 계약은 OHI-06 결정 뒤 고정한다. 일반 업무/관리 API는 계속 Authorization 공개 조건을 기다린다.
- 선행 결정: 세션/토큰 발급·종료·실패 응답·Credential lifecycle(OHI-06), 휴직 신규 로그인·기존 세션 조합(OHI-02).
- 종료 검증: 정상 인증, 잘못된 인증 정보, 비활성 User, 퇴사 Employee, 위조 신원·로그아웃 후 재사용·민감 정보 누출. IAM-T10/11의 신규 로그인 부분, SEC-T01/03.
- 휴직 처리: OHI-02 OPEN을 유지한다. 재직/퇴사 시나리오의 격리 검증은 가능하지만 휴직 결과를 임의 기본값으로 채우지 않는다. 해당 계약이 없으면 휴직을 포함하는 로그인 기능의 공개·완료 판정을 보류한다.

#### AN-03 요청별 유효 접근 신원

- 범위: 이미 발급된 인증 자료를 가진 요청에서도 최신 계정·재직·소속·역할 근거를 일관되게 구성하고 퇴사/비활성화 이후 새 요청을 차단한다.
- 경계: IAM → HR/Organization Published API. 서버 진입 어댑터가 신뢰 가능한 Actor를 구성하고 이후 단계의 인가를 거친다. HR/Organization Application이 IAM을 역호출해 모듈 순환을 만들지 않는다.
- 선행 결정: OHI-06의 기존 세션 효력·재검증·조회 실패 응답·필요 시 캐시 갱신, AQ-04의 Actor 위치/형식, 한 요청의 기준 시점 계약. 매 요청 조회는 후보이지 이번 자동 확정 기술이 아니다.
- 종료 검증: IAM-T10/11/13, HR-T11, SEC-T01. 퇴사/계정 비활성화 커밋 이후 시작하는 기존 세션 요청 차단, 전보·Role 철회 후 낡은 근거 제거, 중간 상태 변경의 일관성, 의존 조회 장애를 성공 신원으로 추측하지 않음.
- 휴직 정책은 OHI-02가 정한 결과를 사용한다. 이미 실행 중인 업무를 소급 중단시키는 정책은 추가하지 않는다.

### 3.5 Authorization

#### AZ-01 행위 권한

- 범위: 첫 대상 업무의 행위 식별과 Role 매핑을 정하고 유효한 Allow의 합집합을 평가한다. Role이 없거나 행위 권한이 없으면 업무를 허용하지 않는다.
- 공개 계약: ActionAuthorization 후보. 서버가 구성한 Actor와 행위·필요한 대상 사실을 입력으로 사용한다.
- 선행 결정: 해당 업무의 정확한 행위·Role 매핑과 비활성 Role 처리(OHI-05), HTTP 외 내부 진입의 신뢰 경계(AQ-04), 인증/인가 실패 응답 구분.
- 종료 검증: IAM-T05의 행위 결합 부분, IAM-T06/13, SEC-T01/02. 복수 Role의 Allow 합집합, 권한 부재, 비활성/철회 Role, 직급만으로 권한 상승 없음, 직접 API와 내부 진입 우회 방지.
- 제한: 행위 허용만으로 대상 접근 허용까지 완료되지 않는다. DENY Role이나 User 전역 Scope를 추가하지 않는다.

#### AZ-02 대상 데이터와 첫 업무 API

- 범위: 먼저 공개할 Organization 또는 HR Use Case 하나를 선택하고 그 도메인의 대상 데이터·민감 필드·조회/변경 접근 계약을 정한 뒤 API 하나를 연결한다. 전 도메인 Scope 표를 만들지 않는다.
- 선행 결정: 선택 도메인에 필요한 OHI-00의 Scope 표현/적용/결합·목록 필터·개별 대상·필드 공개, 해당 API의 입력/오류/재시도 계약. 나머지 도메인의 OHI-00은 OPEN으로 유지한다.
- 후보: Organization 기준정보 조회부터 시작하는 안. 정확한 endpoint 선택과 허용 범위는 후속 결정이며 ALL 또는 ADMIN 전역 우회로 메우지 않는다.
- 경계: 진입부 인가 후에도 소유 도메인이 대상/문서 상태를 확인한다. 목록·단건·변경에 같은 대상 접근 계약을 적용하고 서버 기준의 현재 소속을 사용한다.
- 종료 검증: SEC-T01/03, IAM-T13 및 선택 도메인의 새 접근 행렬 테스트. 다른 직원/대상 ID 직접 접근, 목록/단건 필터 차이, 페이지·정렬을 통한 누출, 다른 Role의 행위와 별개 Scope의 임의 결합, 민감 필드 누출.
- 후속: 첫 API 검증 뒤 나머지 Organization/HR 업무를 같은 크기의 API Slice로 연결한다. 최초 데이터 접근 계약을 다른 도메인으로 자동 복사하지 않는다.

#### AZ-03 권한 관리 API

- 범위: User 관리 상태·Role 정의/상태·UserRole 부여/철회 외부 API를 명령 하나씩 연결한다. 이 단계도 한 번에 모든 endpoint를 공개하지 않는다.
- 선행 결정: OHI-05의 부여 가능한 Role·자기 권한 변경·마지막 관리자 보호·재활성화, OHI-00의 IAM 관리 대상 접근, OHI-06의 bootstrap과 운영 경계.
- 종료 검증: SEC-T02 및 IAM-T07/08/09/11/13. 단순 로그인으로 자기 관리 권한 상승 금지, grantId 지연 철회 보호, 퇴사자 enable로 차단 우회 불가, 동시 관리자 철회/비활성화에 대한 합의된 보호 정책, 직접 관리 API 호출.
- 종료의 의미: 선택한 Organization/HR/IAM 경로의 서버 인증·인가·대상 검증이 실제로 확인됨. ERP 전체 인가·Scope·결재가 완성됐다는 뜻이 아니다.

## 4. OPEN 항목의 선행 위치

| 미결 항목 | 필요한 첫 위치 | 완료 판정의 조건 |
| --- | --- | --- |
| OHI-00 Data Scope 구조·표 | AZ-02의 선택 도메인, AZ-03 IAM 관리 | 그 도메인의 행위/대상 계약만 정한다. 전사 Scope와 User 전역 Scope는 만들지 않는다. |
| OHI-01 효력일·예약/소급·정정·초기 이관 | HR-01, HR-03/04 | 실제로 지원할 입력 범위와 경계일·재시도를 먼저 정한다. |
| OHI-02 휴직 로그인 | AN-02/03; 휴직 기능을 추가하는 Slice | **OPEN 유지.** 신규 로그인과 기존 세션의 기대값 없이 전체 공개 완료로 표시하지 않는다. |
| OHI-03 사용 중 조직 비활성 | ORG-03 | 참조 보존과 신규 배정 차단 외 재배치 강제 여부를 따로 정한다. |
| OHI-04 재입사·퇴사 취소 | 해당 기능을 추가할 후속 Slice | 이번 기본 퇴사/계정 Slice가 지원한 것으로 표시하지 않는다. |
| OHI-05 Role/계정 lifecycle·관리자 운영 | IAM-01~03, AN-01, AZ-01/03 | 저장 lifecycle과 실제 부여 권한·관리자 보호의 계약을 구분한다. |
| OHI-06 인증·Credential·bootstrap·철회 | AN-01~03 | 기술·운영 선택 전 Session/JWT/SSO 중 하나를 구현하지 않는다. |
| OHI-07 조직 계층·부서장·결재 연결 | 후속 Organization/Approval | 이번 Core에서 Position/Role로 대체하지 않는다. |
| OHI-08 키 정규화·변경 | ORG-01 및 각 키 최초 저장 Slice | Company/Site 코드 부분은 ORG-01에서 ADOPTED. Department/Position/Role·사번·loginKey는 후속이며 OHI-08 전체는 OPEN 유지. |
| OHI-09 제약·Guard·동시성 | ORG-01, ORG-03, HR-01/03/04, IAM-01/03 | ORG-01 optimistic 변경 계약은 ADOPTED이며 실제 PostgreSQL 경쟁 트랜잭션으로 검증한다. Guard·기간 제약 등 나머지는 OPEN 유지. |
| AQ-04 Actor/진입 경계 | AN-03, AZ-01 | Published API와 소유권을 유지하며 HTTP 외 호출도 보호한다. |
| DR-25 / E-14 감사 | 해당 변경을 외부 업무 성공으로 제공하기 전 | 필수 기록 범위·실패 계약을 정한다. 임시 로그를 필수 감사의 대체로 처리하지 않는다. |

각 항목은 해당 기능의 계약이 정해진 범위만 해소한다. 한 Slice의 결정으로 관련 OPEN 전체를 닫거나 E-01/E-02를 다시 열지 않는다.

## 5. 테스트 범위와 근거 관리

개발 시 공통 검증 명령은 Foundation의 `backend`에서 `./mvnw -B -ntp verify`다. DB 제약·동시성은 PostgreSQL 16/Testcontainers로 검증하고, Authentication/Authorization은 실제 선택된 인증 어댑터와 HTTP 진입에서 검증한다. 이 계획 작성 중 해당 업무 테스트를 실행한 것은 아니다.

| 검증군 | 완성 단계 |
| --- | --- |
| ORG-T01~03 | ORG-01~03, 배정 통합은 HR-01/03 |
| ORG-T04 당시 문서 Snapshot | 문서 소유 도메인 연동 후속. Core만으로 완료하지 않음 |
| HR-T01~10 | HR-01~04, 조직 경쟁 포함 |
| HR-T11 손상 신원 | HR-02 모델/Port와 AN-03 신원 검증 |
| IAM-T01~04·12 | IAM-01 |
| IAM-T06~09 | IAM-02/03; 직급·결재 연결은 해당 도메인 후속에서도 검증 |
| IAM-T05·10·11·13 | AN-02/03, AZ-01~03에서 실제 접근 검증 완성 |
| SEC-T01~03 | AN-01~03 및 AZ-01~03. 도메인별 대상 테스트를 별도 확장 |
| ARCH-T01/02 | 업무 코드가 추가되는 모든 Slice. SQL 접근은 저장 코드/Migration 리뷰로 별도 확인 |

구현 후 Test Evidence에는 Slice, commit, 실행 환경·명령, 성공/실패, rollback/경쟁 결과, 미검증 범위를 기록한다. 휴직 로그인과 미정 도메인 Scope 테스트의 기대값은 정책 결정 전에 작성하지 않는다. 계획의 조건과 실제 PASS를 구분한다.

## 6. 완료의 경계와 다음 검토 대상

이 계획은 16개 기본 Slice와 선행 결정의 순서를 제시한다. ORG-01의 로컬 검증 상태 외 다른 Slice의 날짜·공수·담당자·구현 완료를 배정하지 않았다. 새로운 ERP 업무, 급여/근태/연차 계산, 실제 결재선, 재고/회계 또는 Frontend 연동 전체를 포함하지 않는다.

ORG-01의 저장·초기 회사·조직 코드 계약은 O1-D01~10 채택으로 확정했다. 이후 사용자 실행 지시에 따라 ORG-01 내부 구현과 필수 로컬 검증을 완료했다. 코드·문서는 미커밋 상태이며 다른 Slice에 진입하지 않았다. 세부 결과는 ORG-01 Test Evidence를 기준으로 한다.

참고 기술 근거:

- [PostgreSQL 16 Constraints](https://www.postgresql.org/docs/16/ddl-constraints.html): FK·행 제약·부분 유일성의 보장 범위.
- [PostgreSQL 16 Range Constraints](https://www.postgresql.org/docs/16/rangetypes.html#RANGETYPES-CONSTRAINT): 기간 중복 EXCLUDE 후보. 연속성/공백은 별도 검증.
- [Spring Modulith Verification](https://docs.spring.io/spring-modulith/reference/verification.html): 모듈 순환과 공개 경계 검증. SQL 접근까지 검사하는 도구로 간주하지 않음.
