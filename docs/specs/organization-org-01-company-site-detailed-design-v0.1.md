# ORG-01 회사와 사업장 상세설계 v0.1

- 문서 상태: **ADOPTED — O1-D01~10 사용자 채택 완료**
- 구현 후속 기록: 2026-10-07 ORG-01 **LOCAL VERIFIED**. [실행 근거](../test-evidence/organization-org-01.md)에 최종 로컬 검증 결과와 한계를 기록했다. 다른 Slice와 OPEN 정책은 유지한다.
- Slice: Implementation Plan v0.1의 **ORG-01만**
- 작성일: 2026-10-07 (Asia/Seoul)
- 채택일: 2026-10-07 (Asia/Seoul)
- 개정: **PM 검토 반영 수정본의 O1-D01~10 전부 채택** — O1-D08·O1-D10·선택 검증 조정을 포함
- Repository / 브랜치: `kimini02/k-flow-erp` / `codex/backend-foundation`
- 확인 커밋: `88347ded6d941c497ccefaf1d8447ce28d196d95`
- 제안 경로: `docs/specs/organization-org-01-company-site-detailed-design-v0.1.md`
- 반영/실행 상태: ORG-01 로컬 구현·필수 검증 완료 / 미커밋 working tree / GitHub 미반영 / 원격 CI NOT_RUN.
- 근거: [Implementation Plan](../plans/organization-hr-iam-implementation-plan-v0.1.md), [상세설계](organization-hr-iam-detailed-design-v0.1.md), [ADR 0001](../adr/0001-organization-employee-account-boundaries.md), [ERD](../domain/erd-overview.md)

## 1. 범위와 기존 결정

ORG-01의 종료점은 **Company 1개 초기화 + Site 등록·기본 조회 + 최소 Published 조회 계약 + 실제 DB 검증**이다. Stable ID와 version 검증을 위해 Company/Site의 표시명 정정 계약도 최소 수준으로 정의한다.

E-01/E-02는 CLOSED를 유지한다. 단일 법인, 복수 사업장 지원, Department·Site·Warehouse 구분 및 기존 모듈 소유권을 변경하지 않는다. Company가 단일 법인인 것과 Site가 여러 개일 수 있는 것은 별개다.

2026-10-07 사용자가 PM 검토 반영 수정본의 O1-D01~10 추천안을 모두 채택했다. 이 문서의 ORG-01 계약과 필수 테스트 명세는 해당 채택 범위의 기준이다. OHI-08은 Company/Site 코드 부분만 다루고, Department·Position·Role·사번·loginKey 규칙으로 자동 확대하지 않는다. OHI-09도 ORG-01의 제약과 낙관적 변경 계약만 다룬다. 선택적 Flyway 검증을 필수 완료조건에서 제외한 PM 조정도 유지한다.

확인 커밋에서는 ADR·Implementation Plan이 저장소에 있고 ERD의 E-01/E-02도 CLOSED다. ADR/Plan 본문의 1459447 기준 설명은 해당 문서 작성 시점 기록이며, 상세설계의 기준 HEAD는 88347de다. 해당 HEAD에는 Organization metadata만 있었으며, 현재 로컬 working tree에는 ORG-01 업무 클래스·Migration·테스트가 추가되어 있다. 기준 HEAD 자체에 구현이 포함된 것으로 해석하지 않는다.

ORG-02/03, 직원 배정, IAM, 인증·인가, Site 활성/비활성 명령 및 배정 Guard로 넘어가지 않는다. Data Scope 구조·표와 휴직 로그인은 기존 OPEN을 유지한다. ORG-01 로컬 구현·검증은 완료했으며, 이번 보정은 상세설계와 Test Evidence의 시점·근거 표현만 정리한다. Java·SQL·테스트 코드와 Controller를 추가하거나 수정하지 않는다.

## 2. 채택한 결정 — O1-D01~10

| ID | 상태 | 결정 대상 | 검토 후보 | 채택 내용과 이유 |
| --- | --- | --- | --- | --- |
| O1-D01 | ADOPTED | Company 초기화 | 버전 Migration / 매 기동 자동 seed / 일반 초기 설정 API | **버전 Migration으로 1회 초기화.** 배포 이력과 seed를 함께 관리하며 기동 때 이름·ID를 덮어쓰지 않는다. |
| O1-D02 | ADOPTED | 초기 Site | 3개 자동 seed / Site 없이 시작 | **Company만 seed, 초기 Site 0개 허용.** Site는 내부 등록 Use Case로 추가한다. 본사·화성·용인은 예시이며 자동 등록값으로 채택하지 않는다. |
| O1-D03 | ADOPTED | Stable ID | 증가 숫자 / UUID v4 / UUID v7 | **PostgreSQL uuid + 서버 UUID v4.** Company는 초기 seed용 UUID 하나를 고정하고 Site는 등록 때 생성한다. |
| O1-D04 | ADOPTED | 최소 상태 | Company/Site 모두 상태 / Company 상태 없음·Site 상태 있음 | **Company 상태 없음, Site ACTIVE·INACTIVE.** Site 등록은 ACTIVE만, 전이 명령은 ORG-03에 남긴다. |
| O1-D05 | ADOPTED | 코드 비교 | 입력 원문 / 정규화값과 원문 둘 다 저장 / canonical code 하나 저장 | **정규화된 code 하나만 저장.** ASCII 양끝 SPACE 제거·허용 문자 검증·ASCII 대문자 변환 후 중복 검사한다. |
| O1-D06 | ADOPTED | 코드 namespace·재사용 | 전 조직 전역 / Entity별·Company별 / 활성 행만 유일 | **Company/Site namespace 분리, Site는 Company별 유일. 비활성 상태에도 재사용 금지.** |
| O1-D07 | ADOPTED | 코드 변경 | 자유 변경 / 이력 포함 변경 / 생성 후 불변 | **v1 Company/Site code 불변.** 표시명은 수정할 수 있고 참조는 ID를 사용한다. 코드 정정 기능은 별도 요구가 생기면 설계한다. |
| O1-D08 | ADOPTED | Company 1개 | Application 조회 검사만 / Flyway seed + DB singleton + Runtime 건수 검증 | **Flyway seed + DB singleton + Runtime에서 정확히 1건 존재 검증.** 고정 seed UUID/code를 Application magic constant로 복제하거나 값 일치 검사를 하지 않는다. |
| O1-D09 | ADOPTED | version | 시각 / 정수 / 없음 | **BIGINT version + 기대 version + 낙관적 갱신.** 읽기·기동은 version을 바꾸지 않는다. |
| O1-D10 | ADOPTED | Published 조회 | generic Entity 반환 / 목적별 최소 단건 DTO 조회·내부 목록 분리 | **cross-module Published Port는 Company 단건·Site 단건의 2계약만.** Site 목록·pagination/sorting은 Organization 내부 조회 계약으로 분리한다. 현재 사실만 반환하고 배정 허가는 제공하지 않는다. |

위 O1-D01~10은 모두 **ADOPTED**다. Java 시그니처·클래스명·Constraint 이름과 seed UUID literal의 현재 구현은 로컬 코드와 Test Evidence에서 확인한다. 채택한 의미 계약과 다른 Slice의 후보 상태는 이번 문서 보정으로 변경하지 않는다.

## 3. Company 초기화

### 3.1 채택 방식

이미 Foundation에서 활성화된 Flyway의 **versioned Migration**에 Company/Site schema와 Company 초기 행을 포함하는 채택안을 구현했다. 일반 Company 생성 API나 매 기동 check-then-insert seed를 만들지 않는다.

| 초기 항목 | 후보 값 / 규칙 |
| --- | --- |
| Company ID | seed용 UUID v4 하나를 정하고 Migration에 고정. 현재 literal은 Migration에 기록되어 있으며 매 환경·재기동에서 새로 생성하지 않음 |
| singletonKey | 1 |
| code | HANGYEOL |
| name | 한결 인더스트리 |
| version | 0 |
| createdAt / updatedAt | 최초 seed 시 같은 기록 시각 |
| Site | 초기 행 없음. 0개는 정상 목록 결과 |

고정 UUID는 이 K-Flow v1 데이터셋의 같은 법인을 식별한다. Company 이름이나 code를 해시해 ID를 계산하지 않는다. 서로 다른 운영 법인을 같은 seed로 지원하려는 다법인/테넌트 설계는 아니다.

고정 UUID와 초기 code literal은 Migration의 seed 정의에만 둔다. Application은 현재 Company 행에서 ID/code를 읽어 사용한다. Migration의 초기값을 Application 상수나 설정으로 복제해 Runtime 검증 기준으로 삼지 않는다.

Flyway 버전 Migration은 적용 이력과 checksum으로 관리된다. 적용된 Migration을 편집해 재실행하지 않고, 이후 변경은 새 버전으로 다룬다. 반복 실행된 기동이 현재 Company 표시명을 초기 이름으로 복구하지 않는다. [Flyway versioned migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)

### 3.2 준비 순서와 실패 계약

1. 빈 DB 또는 현재 Foundation DB에서 ORG-01 Migration을 적용한다.
2. schema·Company seed를 같은 transactional Migration 단위로 완료한다. 트랜잭션 밖 실행이 필요한 DDL은 이 초기 Migration에 섞지 않는 후보를 추천한다.
3. 초기화 후 읽기 전용 Runtime 검사를 한다. Company가 **정확히 1건 존재하는지**만 확인한다. 고정 seed UUID/code와의 일치 여부를 검사하지 않으며 singletonKey·code 문법은 DB 제약의 책임으로 둔다.
4. 확인 실패 시 정상 준비 완료로 보지 않는다. 임의 첫 Company를 선택하거나 행을 삭제·재생성해 맞추지 않는다.
5. 정상 기동 이후 재기동은 Migration 이력을 확인하고 같은 Company를 읽는다. ID·name·version·기록 시각을 seed 값으로 덮어쓰지 않는다.

정확히 1건이면 저장된 Company ID/code를 현재 법인의 값으로 사용한다. 초기 seed literal과 다르다는 이유로 COMPANY_CONFIGURATION_INVALID를 발생시키지 않는다. 표시명도 초기 이름과 비교하지 않는다. 이는 일반 명령에서 ID/code를 변경해도 된다는 뜻은 아니며, 저장 후 불변 계약은 유지한다. 이 검사는 회사 초기화이며 IAM 초기 관리자·사람 계정 bootstrap과 관계없다.

실패한 transactional Migration은 부분 schema/Company를 남기지 않아야 한다. DB 연결·Migration·Runtime 건수 검사 실패를 성공 기동으로 감추지 않는다. **Flyway 동시 최초기동과 Migration 실패주입 검증은 10.2절의 선택적 Foundation/운영 검증이며 ORG-01 필수 완료조건이 아니다.** [Flyway transaction handling](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)

### 3.3 정확히 1개의 보장 범위

- DB의 singleton 제약은 **행이 최대 1개**임을 보장한다. 행이 적어도 1개 존재한다는 보장은 아니다.
- 최소 1개는 정상 Migration의 seed와 Company 생성/삭제/ID 교체를 제공하지 않는 Application 경로로 유지한다.
- 기동 및 Company가 필요한 읽기·변경에서 정확히 1건 존재를 확인하고, 0건/복수 건은 오류로 감지한다. 저장된 ID/code와 seed literal의 일치 여부는 검증하지 않는다. Site가 없을 때 권한 있는 운영 SQL이 Company를 삭제하는 것까지 PK/UNIQUE/FK가 막는다고 주장하지 않는다.
- 운영 중 Company가 사라지면 Site 등록/Company 조회는 무결성 오류다. 자동 재생성하지 않는다.
- Company DELETE 방지 trigger, DB role 분리 또는 운영자 권한 정책을 이번 Slice에서 추가 설계하지 않는다.

## 4. Entity 책임과 최소 필드

Company/Site는 Organization 소유다. Site.companyId는 같은 법인의 Stable ID 참조이며, Department·Warehouse·직원·Role 관계를 Site에 추가하지 않는다.

### 4.1 Company 후보

| 필드 | 타입 후보 | 필수·값 | 변경 규칙 |
| --- | --- | --- | --- |
| id | uuid | 필수, Migration에 고정한 초기 ID | 불변 |
| singletonKey | smallint | 필수, 1 | 불변·외부 DTO 제외 |
| code | varchar(32), C collation | 필수 canonical code, Migration 초기값 HANGYEOL | 불변 |
| name | varchar(100) | 필수 표시명 | 기대 version으로 정정 가능 |
| version | bigint | 필수, 최초 0·0 이상 | 실제 변경 시 저장 계층이 증가 |
| createdAt | timestamptz | 필수 | 생성 후 불변 |
| updatedAt | timestamptz | 필수 | 실제 변경 시 기록 |

Company에는 ACTIVE/INACTIVE를 두지 않는다. 법인 사업자번호·주소·대표자·회계 기본설정·계좌 등을 가상 값으로 채우지 않는다. 후속 업무가 필요한 필드를 별도로 요청한다.

### 4.2 Site 후보

| 필드 | 타입 후보 | 필수·값 | 변경 규칙 |
| --- | --- | --- | --- |
| id | uuid | 필수, 서버 UUID v4 | 불변 |
| companyId | uuid | 필수, 현재 Company ID | 생성 후 불변 |
| code | varchar(32), C collation | 필수, 정규화값 | 생성 후 불변 |
| name | varchar(100) | 필수 표시명 | 기대 version으로 정정 가능 |
| status | varchar(16) | 필수, ACTIVE 또는 INACTIVE | ORG-01 등록 시 ACTIVE만. 전이는 ORG-03 |
| version | bigint | 필수, 최초 0·0 이상 | 실제 변경 시 저장 계층이 증가 |
| createdAt | timestamptz | 필수 | 생성 후 불변 |
| updatedAt | timestamptz | 필수 | 실제 변경 시 기록 |

최소 등록 입력은 code/name이다. Company ID, Site ID, 초기 status/version/기록 시각은 클라이언트가 정하지 않는다. 내부 등록 Use Case가 현재 Company를 확인하고 값을 구성한다.

name은 코드와 다르게 한글 등 Unicode 표시 문자열을 허용하고 이름 중복을 허용한다. 추천 상한은 Unicode code point 100개다. 양끝 SPACE 제거, 빈 이름/공백뿐인 이름/제어문자 거부는 Application 검증이다. DB는 NOT NULL·길이·기본 공백 검사를 마지막 방어로 둔다. Unicode 이름의 모든 검증을 일반 길이 CHECK 하나가 보장한다고 설명하지 않는다.

생성/변경 시각은 저장측이 기록하며 클라이언트 시각을 받지 않는다. Java에서는 Instant, DB에서는 timestamptz로 다루고 UTC로 표시하는 후보를 추천한다. 시각을 변경 충돌 판단의 version으로 사용하지 않는다. 단순 기록 시각은 필수 Audit 저장의 대체가 아니며 DR-25/E-14를 닫지 않는다.

### 4.3 Site 상태의 범위

| 상태 | ORG-01의 의미 |
| --- | --- |
| ACTIVE | 등록 시의 정상 기준정보 상태. 조회에서 현재 상태를 반환 |
| INACTIVE | 후속 비활성 상태를 읽을 수 있는 모델 값. 이력/표시 조회에서 존재가 사라지지 않음 |

ORG-01에서 status를 받는 자유 PATCH나 activate/deactivate를 추가하지 않는다. INACTIVE 조회 테스트는 테스트 전용 fixture로 만든다. 사용 중 Site 비활성화·재배치 강제(OHI-03), 신규 배정 직렬화 Guard는 ORG-03이며 이번 문서에서 결정하지 않는다.

## 5. Stable ID와 version

### 5.1 Stable ID 추천

| 대상 | 추천 |
| --- | --- |
| Company | 최초 seed UUID v4 하나를 Migration에 고정 |
| Site | 서버가 등록 시 UUID v4 생성, PostgreSQL uuid로 저장 |
| 외부 참조 | code/name 대신 ID 참조 |
| 표시명 정정 | 같은 ID를 유지 |
| 중복 UUID | PK가 저장을 거부. UUID가 확률상 충돌 가능성이 낮다는 것을 무충돌 보장으로 설명하지 않음 |

Java 21 기본 UUID 생성으로 v4를 사용할 수 있다. UUID v7 라이브러리·순서 기반 ID 생성기·분산 ID 서버는 ORG-01에 추가하지 않는 추천안이다. UUID의 형식 자체가 Stable ID나 권한을 보장하지는 않으며, 저장 후 불변성이 필요하다. [Java 21 UUID](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/UUID.html), [PostgreSQL 16 uuid](https://www.postgresql.org/docs/16/datatype-uuid.html)

### 5.2 version 추천 계약

- 저장된 최초 version은 0, 타입은 bigint다. 기대 version은 0 이상의 정수로 전달한다.
- 표시명 변경은 Company/Site ID와 expectedVersion을 받고, 읽은 현재 version과 일치하는지 확인한다.
- 확인 뒤 저장에도 읽은 version이 조건으로 유지돼야 한다. JPA version 기반 낙관적 잠금을 초기 후보로 추천한다. 단순 사전 비교 후 조건 없는 UPDATE는 허용하지 않는다.
- 실제 변경 성공은 같은 ID·code·companyId를 유지하고 version이 1 증가한 결과를 반환한다.
- stale version 또는 동시 변경 충돌은 전체 rollback 후 VERSION_CONFLICT로 구분한다. 자동으로 최신 version을 다시 읽어 사용자의 변경을 덮어쓰지 않는다.
- 조회·재기동·중복 등록 실패는 version을 바꾸지 않는다. 클라이언트가 version의 다음 값을 정하지 않는다.
- 동일한 정규화 표시명으로의 요청은 expectedVersion 검증을 포함한 no-op 후보다. 추가 쓰기 없이 version을 유지하며, 낙관적 검증의 완료 시점을 실제 JPA 트랜잭션으로 검증한다.
- Application의 version 필드 존재나 DB의 0 이상 CHECK만으로 Lost Update가 방지됐다고 보고하지 않는다.

실제 JPA provider의 신규 UUID 객체 저장, 최초 version=0, 변경 시 증가, flush/commit 오류와 no-op 검증 시점은 ORG-01 로컬 테스트로 확인했다. 검증 범위와 한계는 [Test Evidence](../test-evidence/organization-org-01.md)를 따른다. 이 문서는 매핑 코드가 아닌 설계 계약을 기록한다. [Jakarta Persistence Version](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/version)

## 6. OHI-08 Company Site 코드 추천

### 6.1 하나의 canonical code

code는 검색·표시용 업무 코드이며 PK가 아니다. 정규화된 code만 저장하고, 입력 원문과 normalizedCode 두 컬럼을 각각 수정 가능한 원본으로 두지 않는다. 기존 DB-02의 normalizedCode는 이 canonical code를 뜻하도록 구체화하는 후보다.

정규화 순서:

1. null은 거부한다.
2. 양끝 **ASCII SPACE U+0020만** 제거한다. TAB·CR/LF·NBSP를 SPACE처럼 조용히 삭제하지 않는다.
3. 제거 후 길이 1~32, 첫 문자는 ASCII 영문자 또는 숫자, 나머지는 ASCII 영문자·숫자·하이픈·밑줄인지 검증한다.
4. 소문자 ASCII a~z를 대문자 A~Z로 변환한다. Unicode 대문자 변환 전에 비ASCII 입력을 거부한다.
5. canonical code로 조회·중복 확인·저장한다.

입력 문법 후보는 `[A-Za-z0-9][A-Za-z0-9_-]{0,31}`, 저장 문법 후보는 `[A-Z0-9][A-Z0-9_-]{0,31}`다. NFKC로 전각 문자를 반각 코드로 바꾸거나 하이픈/밑줄·내부 공백을 제거하지 않는다.

DB code 비교와 패턴 검사는 C collation 기준을 추천한다. 서버 언어 설정에 따라 동일 코드 판정이 달라지지 않게 하고, Application 문법과 실제 PostgreSQL CHECK 일치를 검증한다. [PostgreSQL collation](https://www.postgresql.org/docs/16/collation.html), [Pattern matching](https://www.postgresql.org/docs/16/functions-matching.html)

| 입력 예 | 결과 |
| --- | --- |
| hq / Hq / HQ / 양끝 SPACE가 있는 hq | HQ |
| hwaseong-01 | HWASEONG-01 |
| HQ-1 / HQ_1 | 서로 다른 유효 코드 |
| 001 | 유효 코드 |
| null / 빈 문자열 / SPACE뿐인 값 | 거부 |
| H Q / -HQ / _HQ / HQ.1 | 거부 |
| TAB·줄바꿈·NBSP가 포함된 코드 | 거부 |
| ＨＱ / 한글 코드 / İ / ß | 거부. 전각/Unicode 문자를 ASCII로 바꾸지 않음 |
| 허용 문자 32개 / 33개 | 32개 허용 / 33개 거부 |

### 6.2 중복과 변경 규칙

- Site는 `UNIQUE(company_id, code)`로 유일하다. 회사가 1개여도 회사별 namespace라는 의미를 보존한다.
- Company code와 Site code는 별도 namespace다. Company=HANGYEOL과 Site=HANGYEOL은 형식·중복 규칙상 가능하다.
- Site의 ACTIVE/INACTIVE 모두 같은 UNIQUE 대상이다. 활성 행만의 partial UNIQUE를 사용하지 않는다.
- HQ가 있으면 hq 또는 양끝 SPACE가 있는 HQ를 등록해도 같은 코드 충돌이다.
- name 중복은 허용한다. 같은 Site 이름의 두 행이라도 code·ID가 다르면 별개의 Site다.
- v1 Company/Site code는 생성 후 불변이다. 이름 변경은 ID/code를 변경하지 않는다.
- 비활성 코드 재사용이나 오타 코드 정정을 위해 Site를 삭제·재생성하는 경로는 제공하지 않는다. 별도 코드 정정/alias 요구는 후속 결정으로 남긴다.

사전 중복 조회는 UX/오류 안내를 위한 후보이며 최종 보장은 DB UNIQUE다. 동시에 같은 코드 등록 두 개가 들어오면 하나만 커밋하고 다른 요청은 SITE_CODE_CONFLICT로 구분한다.

등록 후 응답 유실 재시도에서 같은 code를 발견해도, name만 같다는 이유로 기존 Site를 신규 요청의 성공 결과라고 반환하지 않는다. 이번 후보는 중복 충돌과 조회를 통한 결과 확인이며, generic Idempotency 저장소를 만들지 않는다.

## 7. DB-01 DB-02 제약 후보

아래는 제약 설계 표이며 실행 가능한 SQL/Migration 본문이 아니다. 실제 구현과 PostgreSQL 16 기반 검증 결과는 [Test Evidence](../test-evidence/organization-org-01.md)에 기록했다.

### 7.1 DB-01 Company

| 제약 후보 | DB가 보장하는 것 | 별도 책임 |
| --- | --- | --- |
| PK(id), uuid NOT NULL | ID 존재·유일성 | Migration에 초기 ID 고정·Application ID 교체 경로 없음 |
| singletonKey NOT NULL | NULL로 singleton 검사를 우회하지 못함 | 초기값 1 |
| singletonKey CHECK = 1 | 다른 슬롯 값을 사용할 수 없음 | 다법인 지원 없음 |
| UNIQUE(singletonKey) | Company 행 최대 1개 | Flyway seed 후 Runtime에서 정확히 1건 존재 검증 |
| code NOT NULL + 저장 문법 CHECK | canonical 대문자 ASCII 코드·길이 | 초기 literal은 Migration에만 정의. Application은 일반 코드 규칙·생성 후 불변 계약을 지키며 seed 값 일치를 검사하지 않음 |
| name NOT NULL + 1~100 길이·기본 공백 CHECK | 기본 표시명 유효성 | 상세 Unicode 빈 값/제어문자 검증 |
| version NOT NULL + 0 이상 CHECK | version 누락·음수 거부 | 기대 version 조건과 낙관적 변경 |
| createdAt/updatedAt NOT NULL | 기록 시각 누락 거부 | 생성 시각 불변·변경 시 기록 |

Company code에 별도 UNIQUE 인덱스는 추가하지 않는 안을 추천한다. singleton 제약으로 Company는 최대 1행이므로 중복 방어의 목적이 이미 충족된다.

### 7.2 DB-02 Site

| 제약 후보 | DB가 보장하는 것 | 별도 책임 |
| --- | --- | --- |
| PK(id), uuid NOT NULL | Site ID 존재·유일성 | 서버 생성·저장 후 불변 |
| companyId NOT NULL + FK → Company.id | 유효 Company 참조 | 현재 법인 사용·생성 후 이동 금지 |
| FK ON DELETE RESTRICT / ON UPDATE RESTRICT | 참조 중 회사 삭제·ID 변경 거부 | 회사 생성/삭제 경로 미제공. 전파 삭제 없음 |
| code NOT NULL + 저장 문법 CHECK, C collation | 저장 코드 정규형 | 입력 정규화 순서 |
| UNIQUE(companyId, code), 모든 status 포함 | 회사 내 중복 코드 거부 | 중복 오류를 도메인 충돌로 변환 |
| name NOT NULL + 1~100 길이·기본 공백 CHECK | 기본 표시명 유효성 | 이름 중복 허용·상세 입력 검증 |
| status NOT NULL + ACTIVE/INACTIVE CHECK | 미정 상태 문자열/NULL 거부 | ORG-01 등록 시 ACTIVE. 전이 명령은 ORG-03 |
| version NOT NULL + 0 이상 CHECK | 누락·음수 version 거부 | 낙관적 변경·충돌 rollback |
| createdAt/updatedAt NOT NULL | 기록 시각 누락 거부 | 클라이언트 값 미사용 |

회사 소속은 ID 값으로 저장하는 후보를 추천한다. Published 조회에서 Company JPA 객체를 Site에 중첩해 노출하지 않는다. HR의 복합 FK·EmployeeAssignment·부서/직급 제약은 이 표에 추가하지 않는다.

NOT NULL과 CHECK는 함께 필요하다. CHECK의 NULL 결과만으로 필수값이 거부된다고 설명하지 않는다. FK는 대상 존재를 보장하며 Site가 신규 배정 가능한지, 정상 상태 전이인지 또는 ID/code가 수정되지 않았는지까지 보장하지 않는다. [PostgreSQL Constraints](https://www.postgresql.org/docs/16/ddl-constraints.html)

## 8. Published Port와 Organization 내부 조회 계약

아래 이름·입출력은 의미 계약 후보이며 최종 Java 시그니처가 아니다. cross-module Published 조회와 Organization 내부 목록 조회의 공개 범위를 분리한다.

### 8.1 OrganizationReferenceQueries — 최소 단건 Published Port

다른 모듈이 사용하는 `OrganizationReferenceQueries`는 기존 `organization::api` Named Interface에 두며 **Company/Site의 필요한 최소 단건 조회만** 제공한다.

| 조회 후보 | 입력 | 최소 반환 | 실패/없음 계약 |
| --- | --- | --- | --- |
| getCompany | 없음. v1 singleton 법인 조회 | CompanyReference | 0개/복수이면 COMPANY_CONFIGURATION_INVALID. 임의 첫 행 선택 금지. seed UUID/code와 비교하지 않음 |
| findSite | companyId, siteId | Found(SiteReference) 또는 NotFound | 유효 Company 확인 후 해당 법인에 Site 없음이면 NotFound. DB 장애는 조회 실패 |

| DTO 후보 | 필드 |
| --- | --- |
| CompanyReference | companyId, code, name, version |
| SiteReference | siteId, companyId, code, name, status, version |

Company에는 활성 상태가 없으므로 DTO에 항상 true인 active를 만들지 않는다. Site의 status로 현재 활성 여부를 알 수 있으며 status와 수정 가능한 active 원본을 이중 보관하지 않는다. version은 충돌 계약/관찰용 revision이지 배정 허가 토큰이 아니다.

단건 Site 조회는 INACTIVE도 반환한다. 테스트 fixture로 후속 상태 조회 계약을 확인하되 상태 변경 기능을 만들지 않는다.

이 Published Port에는 목록 메서드·pageIndex/pageSize·정렬 조건·SitePage를 넣지 않는다. 다른 모듈은 Organization 내부 목록 조회 계약에 의존하지 않는다.

### 8.2 Organization 내부 Site 목록 조회

`SiteListQueries`는 Organization 내부 Application 조회 계약 후보로 둔다. `organization::api` Named Interface의 cross-module Published Port로 노출하지 않는다. 이후 Organization의 목록 API 후보를 위한 계약이며 ORG-01에서 외부 HTTP Controller를 구현하지 않는다.

| 내부 조회 후보 | 입력 | 최소 반환 | 실패/없음 계약 |
| --- | --- | --- | --- |
| listSites | companyId, 선택 status 필터, pageIndex/pageSize | 내부 SitePage | 유효 Company의 Site 0개는 정상 빈 목록. 잘못된 입력/없는 Company/DB 장애는 빈 목록으로 대체하지 않음 |

| 내부 DTO 후보 | 필드 |
| --- | --- |
| SiteListItem | siteId, companyId, code, name, status, version |
| SitePage | items(SiteListItem), pageIndex, pageSize, hasNext |

목록의 status 생략은 두 상태 모두, ACTIVE/INACTIVE 지정은 해당 상태만이다. SiteListItem/SitePage와 pagination/sorting 규칙은 Organization 내부 계약이며 cross-module Published DTO에 추가하지 않는다.

페이지 후보는 pageIndex 0 이상, 기본 pageSize 20·범위 1~100, 정렬 code ASC 다음 siteId ASC다. 임의 컬럼 정렬과 전체 Entity 목록 반환은 제공하지 않는다. hasNext는 다음 행 유무로 반환하고 최소 계약에 totalCount를 넣지 않는다. 여러 페이지 간 동시 등록을 고정 snapshot으로 보장하는 계약은 아니다.

### 8.3 조회의 공통 경계

companyId가 DB에서 읽은 현재 Company의 ID와 다르면 COMPANY_NOT_FOUND로 구분한다. 비교 대상 ID를 고정 seed 상수로 두지 않는다. Company가 0건/복수 건이면 COMPANY_CONFIGURATION_INVALID다. 두 경우를 정상 빈 데이터로 감추지 않는다.

Port는 DB에서 확인한 현재 사실의 불변 DTO만 반환한다. JPA Entity/Repository/Servlet/개인정보를 노출하지 않는다. Permission·Scope·actor·Warehouse·부서·직원·Credential 필드는 없다. 조회 목적별 행위 권한·외부 필드 공개는 후속 인증/인가 설계의 책임이다.

**이 조회는 신규 배정의 최종 허가가 아니다.** ACTIVE를 읽은 직후 변경될 수 있으며 version을 받았다는 이유로 저장 시 상태가 보장되지는 않는다. 배정 Guard와 그 동시성은 ORG-03/후속 HR Slice에 남긴다. 과거 이름/상태 snapshot을 복원하는 조회도 아니다.

## 9. 내부 Use Case와 API 경계

| ORG-01 내부 후보 | 성공 결과 | 원자성/실패 |
| --- | --- | --- |
| Company 초기화 | singleton Company 준비 | Migration 단위 실패 시 원복, Runtime에서 정확히 1건 존재 검증 실패 시 준비 완료 거부 |
| Site 등록 | ACTIVE Site 1개, 새 ID·canonical code·version 0 | 없는 Company·코드/이름 검증 실패·중복이면 미저장 |
| Company/Site 단건 조회 | 8.1절의 Published DTO | Company 0건/복수 건·DB 장애와 Site NotFound를 구분 |
| Site 목록 조회 | 8.2절의 Organization 내부 SitePage | 유효 Company의 빈 목록과 입력/Company/DB 오류를 구분. cross-module 비공개 |
| Company/Site 표시명 정정 | 같은 ID/code/companyId, 실제 변경 version 증가 | stale version/저장 실패이면 원복 |

표시명 정정은 Stable ID/version 확인에 필요한 최소 범위다. code·companyId·status·법인 수를 바꾸는 일반 PATCH로 확장하지 않는다.

Plan의 GET company, GET/POST sites는 후보를 유지한다. ORG-01에서는 내부 Application/Port/DB 검증으로 종료하고 HTTP Controller를 외부 공개하지 않는다. Company POST/DELETE, Site DELETE/activate/deactivate, 권한 임시 우회는 포함하지 않는다.

내부 오류 후보는 INVALID_ORGANIZATION_CODE, INVALID_ORGANIZATION_NAME, SITE_CODE_CONFLICT, VERSION_CONFLICT, COMPANY_NOT_FOUND, COMPANY_CONFIGURATION_INVALID다. HTTP 상태/존재 은폐/인증 실패와 매핑하는 최종 계약은 외부 API 공개 시점에 정한다.

## 10. ORG-T01 테스트 종료조건

아래는 **필수 테스트 명세**다. ORG-T01의 원래 조건인 'Company가 1개일 때 다른 Company 등록 시 v1 법인 1개 유지'를 정상 초기화·DB singleton·Runtime 건수 검증으로 세분화한다. 이 표와 10.1절은 필수 완료조건이고, 10.2절은 선택 검증이다. 실제 실행 결과와 fixture 범위는 10.3절 및 Test Evidence를 따른다.

| ID | Given / When | Then |
| --- | --- | --- |
| ORG-T01-A | 빈 DB 또는 업무 table 없는 Foundation / 정상 Migration | Company 정확히 1개, Migration에 정의된 seed ID·code·slot·version 0, Site 0개, Runtime 건수 검증 정상. seed 값 비교는 Migration 테스트에만 두고 Application 검증에 복제하지 않음 |
| ORG-T01-B | 초기화된 DB / migrate·앱 기동을 반복 | Company 1개·같은 ID·name·version·생성 시각. seed overwrite 없음 |
| ORG-T01-C | Company 1개 / 다른 UUID·singletonKey 1의 두 번째 Company 저장 시도 | DB UNIQUE 위반, rollback 후 기존 Company만 유지 |
| ORG-T01-D | Company 1개 / singletonKey 2·NULL로 우회 | CHECK 또는 NOT NULL 위반, 기존 Company 유지 |
| ORG-T01-G | 적용 이력은 있지만 Company 0건/복수 건인 격리 fixture / 기동·조회 | COMPANY_CONFIGURATION_INVALID, 자동 재생성/첫 행 선택/정상 빈 결과 없음 |
| ORG-T01-H | 정상 Company의 표시명 정정 / 재기동 | 수정 이름·같은 ID·새 version 유지. 초기 이름으로 복구하지 않음 |
| ORG-T01-I | DB 제약을 만족하는 Company가 정확히 1건이며 ID/code가 초기 seed literal과 다른 격리 fixture / 기동·조회·Site 등록 | 건수 검증 통과, 저장된 ID/code 반환, Site는 저장된 Company ID를 참조. 고정 seed 값 불일치를 오류로 처리하지 않음 |

두 Company가 있는 손상 fixture는 격리 테스트 schema에서만 만든다. 정상 schema의 singleton 제약을 구현이나 배포에서 끄지 않는다. 복수 행 fixture에서는 어느 Company든 임의로 골라 정상 처리하지 않는지도 확인한다.

ORG-T01-I는 Application에 seed UUID/code 상수 검증이 없음을 확인하는 격리 fixture 테스트다. 운영 중 Company ID/code 변경 명령을 허용하거나, 정상 Flyway seed 값을 환경마다 바꾸는 계약이 아니다. Stable ID와 code 불변 추천은 유지한다.

Application에는 일반 Company 생성/삭제 경로가 없어야 한다. 그와 별개로 직접 DB 쓰기 테스트로 두 번째 Company 저장이 마지막 방어에서도 거부되는지 확인한다.

### 10.1 ORG-01 추가 종료 검증

| ID | Given / When | Then |
| --- | --- | --- |
| O1-T01 | hq / Hq / SPACE로 감싼 HQ 등록 | canonical HQ로 비교·저장. 기존 HQ가 있으면 충돌 |
| O1-T02 | 문자·길이 경계/전각·Unicode·내부 공백·제어 공백 코드 | 6.1절과 같은 허용/거부. DB 우회 소문자 저장도 거부 |
| O1-T03 | 같은 Company에 동시 hq/HQ 등록 | 한 행만 커밋, 나머지는 코드 충돌. 이름 비교로 성공 처리하지 않음 |
| O1-T04 | INACTIVE HQ fixture / HQ 신규 등록·조회 | 신규 등록 충돌, 기존 단건 DTO는 INACTIVE 상태로 식별 가능 |
| O1-T05 | 이름이 같은 두 Site / 서로 다른 code로 등록 | 각각 별개 ID로 정상 저장 |
| O1-T06 | 현재 Company의 저장된 code와 같은 Site code 등록 | Entity namespace가 달라 형식·중복 규칙상 허용 |
| O1-T07 | 없는 Company·NULL 필수값·잘못된 status·중복 PK 직접 저장 | FK·NOT NULL·CHECK·PK로 거부. 허용된 내부 등록은 ACTIVE만 |
| O1-T08 | Site 표시명 변경 | ID/companyId/code/createdAt 유지, name/updatedAt 변경, version 1 증가 |
| O1-T09 | 같은 expectedVersion의 서로 다른 이름 변경 둘 | 하나만 성공, 하나 충돌. 실패 변경/낡은 덮어쓰기 없음 |
| O1-T10 | stale expectedVersion 및 같은 이름 no-op 요청 | stale 거부. 유효 no-op는 version 유지, 완료 시점 version 검증 확인 |
| O1-T11 | 읽기·재기동·중복 실패 | 원본의 version/기록 시각 변경 없음 |
| O1-T12 | 단건 없음·빈 목록·조회 장애·회사 손상 | NotFound/Empty/조회 실패/무결성 오류를 서로 구분 |
| O1-T13 | Organization 내부 목록의 INACTIVE 포함·ACTIVE 필터·페이지 경계 | 내부 필터·허용 크기·정렬·hasNext 계약 일치. status 생략이면 두 상태 조회 |
| O1-T14 | Company/Site ID·code·companyId 임의 변경 시도 | 공개/내부 명령 계약에서 거부, Stable ID/code/법인 연결 유지 |
| O1-T15 | DTO/모듈·계층 의존 검사 | Published Port는 Company/Site 단건 2계약·최소 DTO만 노출. Site 목록·pagination/sorting·내부 SitePage 노출 없음. JPA/Repository/타 모듈 내부 의존 없음 |

### 10.2 선택적 Foundation/운영 검증

아래 두 검증은 Foundation/운영 관점의 선택 항목으로 내린다. **ORG-01 필수 완료조건과 Test Evidence의 필수 PASS 항목에 포함하지 않는다.** 미실행은 ORG-01 미완료 사유가 아니며, 실행한 경우에만 해당 근거를 별도로 기록한다. 기존 테스트 ID는 추적을 위해 유지한다.

| ID | Given / When | Then |
| --- | --- | --- |
| ORG-T01-E (선택) | 같은 새 DB / 독립 기동 경로 둘이 동시에 Flyway 최초 Migration·검증 | 최종 Company 1개·같은 seed ID, 부분 schema/중복 Company 없음. 정상 준비는 Runtime 건수 검증 통과 후 |
| ORG-T01-F (선택) | transactional 초기 Migration / schema·seed 사이 실패 주입 | 부분 반영 없음, 준비 완료 거부. 실패 원인 해소 후 동일 유효 Migration으로 초기화 가능 |

Site 코드 동시 등록(O1-T03)과 version 충돌(O1-T09/10)은 업무 불변조건이므로 10.1절의 필수 검증으로 유지한다. Migration의 트랜잭션 실패 계약 자체도 유지하며, 이번 조정은 Flyway 동시 최초기동·실패주입 검증의 완료조건 지위만 바꾼다.

### 10.3 실행 근거와 완료 판단

ORG-01은 PostgreSQL 16/Testcontainers와 실제 트랜잭션·저장 경로로 로컬 검증했다. backend의 `./mvnw -B -ntp verify` 최종 실행은 기존 Foundation 회귀를 포함해 67 PASS / failures 0 / errors 0 / skips 0다. 실행 환경·명령·테스트별 매핑은 [Test Evidence](../test-evidence/organization-org-01.md)에 기록했다.

현재 ORG-01은 **LOCAL VERIFIED**다. 검증 대상은 기준 HEAD와 미커밋 ORG-01 변경이며, commit/push 및 원격 CI 검증 완료를 의미하지 않는다. 10절과 10.1절의 필수 검증 매핑은 Test Evidence를 따른다. 복수 Company 응답은 Application 계약 테스트로 확인했고, singleton 제약을 해제한 PostgreSQL 손상 fixture 검증은 NOT_RUN이다. 10.2절의 선택 검증도 NOT_RUN으로 유지하며 필수 종료조건에 포함하지 않는다.

ORG-T02 직원의 부서/Site 독립 배정, ORG-T03 상태 변경/신규 배정 경쟁, ORG-T04 확정 문서 당시 Snapshot 보존은 각각 후속 Slice/도메인 검증이다. 이 문서의 표시명/ID 테스트만으로 그 조건 전체를 PASS 처리하지 않는다.

## 11. 잠재 위험과 확인 위치

아래는 설계에서 예상한 위험과 현재 검증 범위다. 실제 장애가 발생했다는 기록이 아니며, 실행한 검증과 미실행 항목을 구분한다.

| 시나리오 | 왜 위험한가 | 현재 설계가 막고 있는가 | 검증 방법 | 심각도 |
| --- | --- | --- | --- | --- |
| 두 기동 경로가 Company 없음 조회 후 각각 seed | 중복 법인·초기화 경쟁 | 버전 Migration·singleton 제약 구현, 두 번째 Company 저장 거부 확인. 동시 최초기동은 미실행 | ORG-T01-C 필수 PASS / E 선택 NOT_RUN | High |
| UNIQUE만으로 정확히 1개라고 판단하고 Company 누락을 정상 처리 | 사이트 등록/후속 참조의 법인 기준 손상 | Flyway seed·기동/조회 건수 검사 구현, Company 누락 시 실패 확인. 복수 행은 Application 계약 검증 | ORG-T01-G / Test Evidence의 fixture 한계 | High |
| hq/HQ 동시 등록에서 사전 조회만 사용 | 같은 코드가 둘 저장됨 | canonical code·DB UNIQUE 구현, 실제 동시 등록에서 성공 1건 확인 | O1-T01/03 PASS | High |
| 이름 변경에서 version 컬럼만 두고 조건 없이 갱신 | 다른 변경이 조용히 덮어써짐 | 기대 version·낙관적 변경 구현, 변경 경쟁 및 no-op 완료 시 충돌 확인 | O1-T09/10 PASS | High |
| ACTIVE 조회 DTO를 배정 허가로 사용 | 조회 직후 상태 변경 경쟁을 놓침 | 불명확 — ORG-01 조회는 Guard가 아니며 ORG-03 기술 계약 미정 | 후속 ORG-03 / HR-T10 | High |

## 12. 채택 범위와 이번 작업의 종료점

- E-01/E-02 CLOSED 유지.
- O1-D01~10은 2026-10-07 사용자 채택으로 모두 ADOPTED다. 설계 채택 상태와 구현 검증 상태를 구분하며, 현재 ORG-01 구현 상태는 LOCAL VERIFIED다.
- PM 검토에 따라 O1-D08은 Runtime 정확히 1건 존재 검증으로 좁히고, O1-D10은 단건 Published Port와 내부 목록 조회를 분리했다. Flyway 동시 최초기동·Migration 실패주입은 선택 검증으로 내렸으며 다른 추천안은 유지한다.
- OHI-08의 Company/Site 코드 부분과 ORG-01 version·DB 제약 후보를 구체화했다. OHI-08/09 전체를 CLOSED로 바꾸지 않았다.
- OHI-03의 사용 중 기준 비활성화, 다른 Entity 코드, Data Scope 구조/표, 휴직 로그인·인증/인가·필수 감사는 후속 상태를 유지한다.
- 다른 Slice 문서·ADR·ERD·Plan 상태를 변경하지 않는다. ORG-02로 넘어가지 않는다.
- 이번 보정에서는 문서 정합성만 수정하며 Java·SQL·테스트 코드를 변경하거나 테스트를 재실행하지 않는다. 기존 최종 성공 로그와 개별 파일 SHA-256 목록은 그대로 보존한다.

채택한 묶음은 Company 초기화·최소 필드/상태·canonical code·Stable ID·version·DB-01/02·조회 Port·ORG-T01 필수 종료조건이다. ORG-01 로컬 구현·검증 결과를 기준으로 문서의 시점 표현을 정리했다. 이번 작업은 문서/Evidence 보정에서 멈추며 commit/push, 원격 CI 실행 및 다음 Slice 구현은 수행하지 않는다.
