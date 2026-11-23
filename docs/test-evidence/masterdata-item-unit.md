# Item · Unit 선행 Provider — 로컬 구현 Evidence

- 작업: PM5 Gate A 선행 구현. Item/Unit 및 최소 Published Port만 구현.
- 상태: LOCAL_VERIFIED — Item 중심 검증 및 전체 회귀 PASS. PM5 코드 리뷰 대기.
- 기준: `main@3571e90c701baad59b626115f5973d5927fe4d83` (2026-11-22 원격 재조회).
- 로컬 브랜치: `codex/masterdata-item-unit`. 구현 당시 기준 SHA는 구현 커밋이 아니다.
- 원격 제출: 이번 작업의 push / PR / merge / 배포는 미수행. 새 브랜치 CI는 NOT_RUN.
- 기존 main CI: [44991081550](https://github.com/kimini02/k-flow-erp/actions/runs/44991081550), 같은 기준 SHA / push / success. 새 Item 코드 검증 결과로 재사용하지 않는다.

## 직접 읽은 설계와 승인 경계

| 원문 | 확인 내용 / 지위 |
|---|---|
| [PUR-INV-01 R0.3 최종 국소 보완본](https://drive.google.com/file/d/1sPYmW9Wzm5IUuzPSo1L2T0pv3zMHgDts/view) | 문서 자체 PROPOSED. 이번 사용자 지시의 PM5 기술 검토 PASS와 Gate A 선행 구현 승인을 적용. PI-BQ01~03 선택 방향, Gate B NO-GO, P-01~06 OPEN 유지. |
| [R0.3 최종 Unified Diff](https://drive.google.com/file/d/1LvUsXz7A2OFtEW6_cGctJ3p4h53pFOsv/view) | D-01 Item KS 소유 Port / 선잠금, D-03 Root rollback, D-04 보호 Command 등 실제 수정 본문 확인. |
| [Item 상세설계 R1](https://drive.google.com/file/d/1w05HyBlAWoSXlVhpX-RqotiQHBbXX68H/view) | 기존 사용자 B안 및 IT-BQ01~05 승인 정책. IT-D01~20 기술안은 PROPOSED 유지하며 아래 구현 선택을 PM5 리뷰에 제출. |
| [Claude Independent Delta Review R2](https://docs.google.com/document/d/1dVvIDO8VOL2VmMgCpy4yMmIwnNVlQAUOTnFgfXq-BdI/edit) | CONDITIONAL PASS, Critical/High 0, Medium 4 / Low 3. 문서 보완과 실제 PG 실증을 구분. 최종 R0.3에 반영된 국소 수정과 대조. |

원문을 실제 읽었으며 링크만 전달받고 읽었다고 간주하지 않았다. 원문에 남은 당시의 구현 금지/승인 대기 문구는 역사적 작업 상태이다. 이번 구현 승인을 근거로 선행 범위만 진행하며 원문 전체를 ADOPTED로 바꾸지 않는다. Requirements DR-01/03, Architecture AQ-01·Published Port·업무 소유자 Orchestration, 기존 ADR의 Organization/HR/IAM 소유 경계와 대조했다. Architecture §6의 masterdata 의존 없음은 DRAFT 예상표다. 이번 회사 범위 Item의 서버 Company 조회에 필요한 masterdata → organization::api 의존을 추가했으며 소유권 채택을 바꾸지 않는다. 이 차이는 본 Evidence에 기록하고 기존 Architecture 문서를 재작성하지 않았다. Item/Unit은 masterdata, Company는 organization, 미래 Partner는 masterdata, Warehouse는 inventory 소유를 유지한다.

## 실제 구현 경계와 기술 선택

- `masterdata_item`: UUID v4 Stable ID, 서버 Company, 회사별 canonical code 전 상태 UNIQUE, 이름/규격, category/usageProfile/stockManaged, baseUom, 선택 참고가, 상태/version, 최초 사용 진단 tuple.
- 기술 표현은 R1의 세 초기 조합으로 한정: FINISHED_GOOD/MANUFACTURED_FINISHED/true, RAW_MATERIAL/PURCHASED_MATERIAL/true, MERCHANDISE/RESALE/true. DR-01 역할을 표현하는 로컬 구현 선택이다. 내부사용·혼합조달·임의 false 재고관리 조합은 거부. 새 업무 정책 승인으로 확대하지 않는다.
- Unit은 승인된 EA/SET/BOX/KG의 고정 카탈로그로 구현. EA/SET/BOX 정수, KG 소수 3자리, 양수·정확한 BigDecimal. `1.0000 EA`는 정수이며 허용, `1.5 EA`/`0.0001 KG` 거부. 환산·새 단위 등록/정밀도 수정 테이블/API는 만들지 않았다.
- 코드 ASCII SPACE trim → ASCII 검증 → ROOT uppercase. 이름/규격은 Unicode codepoint 길이, ISO 제어문자/단독 surrogate/전체 공백 거부. 이름 중복은 허용.
- 참고가격: NULL과 0 구분, KRW/기본 UOM 1단위/VAT 별도, 정수 원. `NUMERIC` 무지정 scale + CHECK로 소수의 무음 반올림을 막음. 999999999999 상한은 R1 기술 후보의 구현 선택이며 거래 가격/수량 정책이 아니다.
- UOM 실제 변경은 옛 참고가를 자동 승계하지 않는다. 명시 새 가격은 같은 원자적 UPDATE에 반영, 없으면 NULL. 가격 단독 수정은 expectedVersion + 관찰 UOM을 검사한다.
- 최초 사용: PR_APPROVAL/PO_CONFIRMATION/STOCK_POSTING 진단 종류와 source UUID. 같은 consumer Root에 기록되고 취소/비활성/재활성으로 지워지지 않는다. 실제 원천의 진위나 승인/확정은 consumer 소유이며 이 Provider 시험에 실제 문서는 없다.
- JPA Entity 대신 **불변 Item aggregate + 기존 Hibernate Session의 JDBC adapter**를 선택했다. R1의 `@Version` 후보 대신 `WHERE company_id/id/version` CAS와 명시 +1 UPDATE를 사용한다. 사용자 승인 업무 규칙은 동일하며 기술 선택은 리뷰 대상이다. 장점: 소비자의 영속성 컨텍스트를 건드리지 않고 항상 DB 최신 scalar 사용, auto-flush/refresh 잠금 승격 회피. 비용: SQL/매핑/CAS를 직접 유지하고 Item 스키마는 별도 PG 검증 필요. 기존 Hibernate Entity validate는 ORG/HR/IAM을 계속 검증하며 Item까지 검사한다고 주장하지 않는다.
- 관리자 내부 명령: standalone writable RC TransactionTemplate, ambient TX 거부, 성공 응답은 execute의 실제 commit 이후. stale 우선, 실제 변경 +1, no-op +0, BIGINT overflow 원복.
- Guard: PROPAGATION_MANDATORY + 실제 JPA 참여/물리 Connection·DB isolation/readOnly 검사. metadata null이어도 DB READ COMMITTED 검증. 실패를 caller가 잡아도 유효한 Root는 rollback-only.
- 변경/업무 Guard는 `FOR UPDATE`; 참조 Guard는 `FOR KEY SHARE`. PostgreSQL `ORDER BY id`로 중복 제거된 전체 집합을 잠근다. Java UUID.compareTo 정렬에 의존하지 않는다. 모든 ID를 한 번에 전달하고 소비자가 뒤늦게 역순으로 다른 ID를 추가하지 않는 계약이다.
- lock_timeout: 기존 소비자 비영(0이 아닌) 설정 유지, 없을 때만 5000ms 기술 기본값을 잠금/쓰기 구간에 적용 후 복원. 성능 측정이나 운영 SLA 확정이 아니다. 55P03/40P01을 코드 중복 또는 version 오류로 바꾸거나 자동 재시도하지 않는다.
- Item 참조 조회에는 가격을 포함하지 않는다. 내부 관리 query는 가격을 decimal string으로 반환. 목록은 count와 페이지를 단일 SQL snapshot에서 조회하고 ID tie-breaker 사용. 페이지 간 snapshot 보장은 없다.
- Company 생성 참조는 Organization Published Port만 사용. Guard는 자기 Item의 company_id와 FK로 범위를 검사하여 타 모듈 테이블을 직접 읽거나 JPA Company query auto-flush를 발생시키지 않는다.

공식 기술 확인: [PG16 행 잠금](https://www.postgresql.org/docs/16/explicit-locking.html), [PG16 NUMERIC](https://www.postgresql.org/docs/16/datatype-numeric.html). 공식 문서가 이 구현의 실행 증거를 대신하지 않는다.

## 내부 API와 Published Port

| 위치 | 구현 / 보장 |
|---|---|
| `masterdata.application.ItemService` | create, describe, changeAttributes, changePrice, changeStatus, find, list. 내부 Application 명령/조회이며 외부 관리 API 아님. |
| `ItemReferenceQueries.findItem` | 가격 없는 현재 사실, inactive 조회 가능, 회사 범위를 벗어나면 반환 없음. |
| `UnitReferenceQueries` | 고정 단위 목록과 동일 기본 단위의 수량 정밀도 검증. |
| `ItemBusinessUseGuard.lockForNewUse` | 현재 ACTIVE·용도·관찰 UOM/profile/stockManaged 검사, 원자적 최초 사용. PR/PO의 경우 구매 역할만 허용. 전체 Item version만 달라졌다는 이유로 거래를 거부하지 않음. |
| `ItemFulfillmentGuard.lockForExistingPoReceipt` | 이미 사용된 구매 품목의 현재 의미값을 U로 보호. inactive 예외는 ACTIVE 조건만 완화. 신규 사용을 표시하지 않음. |
| `ItemReferenceGuard.protectForDraft` | 동일 RC Root의 회사/ID 참조 보호 KS. 실제 사용/적격성 승인 아님. 미래 PO Header 또는 소비자 INSERT/flush 전에 호출해야 함. |

현재 Production consumer는 **0개**이며 ArchUnit으로 동결한다. 향후 승인된 보호 Purchasing Command가 생길 때 구체 클래스 허용과 실제 원천 검증을 함께 추가해야 한다. 임의 소스 ID를 전달하면 거래가 승인된다는 의미가 아니다. LOT/검사 미설정도 통과 가능한 구매 거래라는 의미가 아니다. P-05의 실제 판별 제공자 없이 PO/GR을 열 수 없다.

**HTTP API 0개 추가.** 현재 IAM Action은 Site read/grant 범위여서 Item 권한으로 전용할 수 없다. Item용 Action/대상 범위/가격 권한/Audit가 미결이므로 공개하지 않았다. 기존 6개 OpenAPI path와 일반 업무 default deny를 유지했다.

## Migration / 기존 보장 보존

신규 [V12](../../backend/src/main/resources/db/migration/V12__masterdata_item.sql)만 추가. V1~V11 원문 변경 없음. Item seed·가상 관리자·Partner·Warehouse·PO·GR 테이블 없음. Unit은 고정 코드 CHECK이며 별도 편집 가능한 Unit 테이블을 임의 설계하지 않는다.

`FoundationIT`는 기존 모든 테이블/Entity/설정/OpenAPI 검증을 유지하고 테이블에 masterdata_item을 더하고 적용 Migration 수를 12로 변경했다. 기존 7개 회귀 테스트에서는 전체 최신 Boot 기동 뒤 버전 기대값만 11→12로 갱신했다. HR의 V3→최신 Migration 개수는 8→9로 확장했고, Session의 V10→V11 전용 시험은 target(11)을 명시했다. 과거 업그레이드·checksum/데이터 검증을 삭제하거나 완화하지 않았다.

`ItemMigrationIT`: V11 선설치 → 기존 조직/Role 데이터 삽입 및 전체 기존 테이블 snapshot → V12 적용 → 모든 기존 rows/constraints/checksums 보존 → 재적용 0건 → Flyway validate / Boot Hibernate validate. 새 Item 스키마는 컬럼 목록·NUMERIC scale·PK/회사코드 인덱스·빈 테이블을 실제 DB로 검사하고 ItemIT에서 CHECK/FK/UNIQUE를 직접 검증한다. 빈 DB V1~V12는 FoundationIT/ItemIT가 별도 검증한다.

## IT-D01~20 구현 대응 (기술 원문의 PROPOSED는 유지)

| ID | 대응 |
|---|---|
| D01 | masterdata Item immutable aggregate / UUID / Company FK |
| D02 | ItemValues.code / company-code UNIQUE / 실제 commit·rollback UNIQUE 경쟁 |
| D03 | codepoint name/specification, 일반 표시 수정 / DB Unicode 공백 제약 |
| D04 | 세 초기 profile 조합, 미지원 조합 거부; 내부사용은 미구현 |
| D05 | Unit enum / UnitReferenceQueries / 무환산 정확한 양수 정밀도 |
| D06~07 | firstUse tuple, 같은 Root 원자성, 기존 표지 보존 |
| D08 | U / KS 구분, PostgreSQL UUID 정렬, tuple 모드 직접 관찰 |
| D09 | CAS 버전 구현을 @Version 후보 대신 선택; stale 우선/no-op/overflow 검증 |
| D10 | ACTIVE 생성, 명시 상태 변경, used와 독립 |
| D11~12 | UOM/새 가격 한 UPDATE, NULL/0·정수 원·소수/비유한 DB 거부 |
| D13 | 목적별 Port; 가격 없는 reference, inactive 기존 이행은 Provider 부분만 |
| D14 | 실제 PO/원천행/잔량은 미구현; 소비자 통합 NOT_RUN |
| D15 | writable RC/동일 물리 Root/rollback-only, test-only consumer 검증 |
| D16 | UOM/profile/stockManaged 관찰값 비교, 이름/가격 version 변화 허용 |
| D17 | 지정 UNIQUE만 code conflict; 미래 복합 부모 UNIQUE 추가 연기 |
| D18 | 내부 명령별 DTO/query, HTTP 공개 보류 |
| D19 | hard delete 없음, ID 유지, 비운 코드 재사용 |
| D20 | Provider 검증과 실제 거래 통합/운영 QA 분리 |

## QA 대응 / 실행 분류

Item 중심 실행: `./mvnw -B -ntp -Dtest=ItemValuesTest,ItemArchitectureTest,ModuleArchitectureTest,LayerArchitectureTest -Dit.test=ItemIT,ItemMigrationIT,FoundationIT verify`.

- Item 중심 Surefire 36 + Failsafe 66 = **102 PASS**, failures/errors/skipped 0, BUILD SUCCESS.
- 포함: 신규 ItemValuesTest 30, ItemArchitectureTest 2, ItemIT 63, ItemMigrationIT 1 및 기존 architecture/Foundation 일부.
- 최종 전체 회귀: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./mvnw -B -ntp verify`를 backend 디렉터리에서 실제 실행. 2026-11-22 19:58:53 KST 종료 / 06:49 / exit 0 / BUILD SUCCESS.
- Surefire **203 PASS** (20 suites) + Failsafe **950 PASS** (42 suites) = **1,153 PASS**. Failures **0**, Errors **0**, Skipped **0**. Maven 최종 집계와 동일 실행 JUnit XML을 직접 대조했다.
- 신규 96건(Item unit/architecture 32 + PostgreSQL integration 64)과 기존 1,057건 회귀가 통과했다. ORG/HR/IAM/AN/AZ-02 및 Modulith/ArchUnit 포함. 이는 로컬 전체 검증이며 새 원격 CI는 NOT_RUN.
- 최종 실행 로그에는 실제 PostgreSQL 잠금 SQL, PID/XID, blocker 관찰과 XML suite별 집계, 검증 당시 변경 backend 파일 및 기존 V1~V11 개별 SHA-256을 보존했다. 로컬 사용자명/경로만 표시 치환했고 실행 결과를 재작성하지 않았다.
- 환경: Java 21.0.2 / PostgreSQL 16.16 / Testcontainers 2.0.5 / Hibernate 7.4.5.Final. 실제 실행 버전이며 과거 16.15를 복사하지 않음.

아래는 실행한 Provider 부분의 대응표다. 실제 consumer/운영 시나리오까지 포함한 R1 전체 QA 일괄 PASS를 의미하지 않는다. 수량은 Unit 검증 코드로 검사하며 아직 수량을 저장하는 거래 테이블/수량 DB 제약은 없다.

| R1 QA | Provider 검증 근거 / 범위 |
|---|---|
| T01~02 | canonical code·Inactive UNIQUE·두 INSERT 대기, 소유 Root commit/rollback, 23505와 지정 제약명 |
| T03 | ASCII/codepoint·Unicode blank/control Java/DB 경계 |
| T04 | inactive 현재코드 예약, 미사용 정정 후 이전 코드 재사용, 실패 후 원본 보존 |
| T05~06 | 미사용 중요 속성 변경, 사용 후 거부, 사용 후 일반 표시/가격 수정 및 상태 독립 |
| T07~08 | UOM/가격 원자성, old 관찰 단위 거부, 가격↔UOM 양방향 commit/rollback 경쟁 |
| T09~10 | NULL/0·정수 원·비유한/소수 직접 DB 거부, 단위별 양수 정밀도 |
| T11~12 | 같은 version 상태/수정 경쟁, no-op 선행/변경 선행, stale 우선, no-op 시각/버전 불변 |
| T13~14 | 동일 PID/XID, 한번만 mark/+1, caught Guard 실패 rollback-only, 신규 표지 rollback/과거 표지 보존 |
| T15~16 | 첫 사용↔UOM 및 첫 사용↔비활성 양방향 commit/rollback, 실제 blocking PID/SQL |
| T17 | 이전 reference보다 최신 SQL 사용, 다른 JPA Company/probe의 변경을 auto-flush/clear/detach하지 않음 |
| T18 | no TX/readOnly/RR/serializable/실제 DB readOnly·RR/다른 manager 거부, metadata-null RC 허용 |
| T19 | 지정 CHECK/FK/UNIQUE 이름·SQLSTATE, 부분 first-use tuple, version·비유한 가격 부정 사례 |
| T20 | 단일 statement count+page, ID tie-break, 빈/범위밖 페이지와 literal 검색 |
| T21 | production consumer 없음·owner SQL·외부 관리 API 없음; 실제 GR 전용 caller 통합은 NOT_RUN |
| T22 | 실제 55P03, 의도적으로 잘못된 test-only 소비자 역순 호출의 40P01, 부분 쓰기·commit 실패 rollback. 운영 장애를 재현했다는 뜻 아님. |
| T23 | 빈 DB / V11→V12, 기존 rows·constraints·checksums, Flyway 및 기존 JPA validate |
| T24 | 최소 조합/미지원 거부, 제조완제품 구매 거부·Provider STOCK_POSTING 종류 검증 |
| T25~36 | REQUIRED-INTEGRATION / NOT_RUN. 실제 PR/PO/GR/수불/Partner/Warehouse 없음 |
| T37~39 | DEFERRED 유지 |
| T40 | DOCUMENTED LIMITATION 유지 |

KS의 정확한 tuple 모드와 U 모드는 test DB `pgrowlocks`로 관찰했다. 참조 KS → test-only consumer FK INSERT 순서는 실제 SQL로 확인했지만 **실제 PO Header 순서 또는 전체 거래 lock graph 검증으로 확대하지 않는다.** 역순으로 서로 다른 집합을 여러 번 요청한 test-only consumer의 deadlock은 부정 시험이며 Production 전체 graph에서 deadlock이 없다는 보장이 아니다.

## 실제 개발 중 발견 / 수정한 문제

보존 로그: [development run](masterdata-item-unit-development-run.txt). 의도적인 DB 부정 시험과 아래 개발 실패를 구분한다.

1. 테스트 코드 wildcard import로 JUnit Timeout과 JPA Timeout이 충돌: 필요한 EntityManager만 명시 import. Production 장애가 아닌 테스트 컴파일 오류.
2. 아키텍처 테스트가 기존 `web.package-info` 골격을 HTTP 구현으로 계산: package-info만 제외하고 실제 web 클래스 0개 검증 유지. 업무 보장을 약화한 변경 아님.
3. 새 itemClock이 기본 Clock 후보가 되어 Modulith Moments 기동 실패: 기존 IAM/Session 패턴처럼 `defaultCandidate=false` + 명시 qualifier. 전체 context 재검증.
4. 잠금 SQL을 XID로 추출하면서 첫 잠금 뒤에 XID를 확보해 첫 문장 로그가 빠짐: 잠금 이전에 PID/XID를 확보해 관찰. 실패 시 test-only FK 테이블이 남아 다음 setup을 방해하던 문제도 격리 정리. 잠금 기준을 완화하지 않음.
5. Repository spy에서 주입한 IllegalStateException은 Spring이 InvalidDataAccessApiUsageException으로 변환: 정확한 외부 타입·root cause·메시지와 최종 DB rollback을 검증하도록 기대값 수정. Production 업무 오류 수정으로 포장하지 않음.
6. NBSP만 있는 이름이 Java에서는 거부되나 PostgreSQL POSIX space 검사에서는 저장됨: V12 신규 CHECK에 Java의 whitespace/spaceChar 집합을 명시. 실제 직접 SQL 부정 시험을 통과하도록 수정. 기존 V1~V11은 변경하지 않음.
7. 첫 전체 회귀는 Surefire 203/0/0/0, Failsafe 950/2/0/0으로 실패했다. Session V10→V11 시험의 무지정 최신 target이 V12까지 적용해 예상 1건 대신 2건이 됐고, HR V3→최신 시험의 예상 8건이 실제 9건으로 늘었다. Session target을 11로 명시하고 HR 최신 개수를 9로 확장했다. 기존 원문 Migration·보존 검증은 변경하지 않았으며 이는 최신 스키마 도입에 따른 회귀 시험 기대값 정합성 수정이다. 수정 후 두 번째 전체 verify는 위 1,153 PASS로 완료됐다.

성능/부하/EXPLAIN 측정 결과는 없음. timeout 기본값의 운영 적정성을 실측했다고 주장하지 않는다.

## 변경 파일 범위

총 41개: masterdata Production 24개(기존 package-info 4개 포함), 신규 V12 Migration 1개, 테스트 13개(신규 4개/기존 9개), Evidence 3개. 기존 ORG/HR/IAM Production·Frontend·기존 Migration·CI 설정은 변경하지 않았다.

- Production: `backend/src/main/java/com/kflow/erp/masterdata/{api,application,domain,infrastructure}/`.
- 신규 Migration: `backend/src/main/resources/db/migration/V12__masterdata_item.sql`.
- 신규 테스트: `ItemArchitectureTest`, `masterdata/ItemValuesTest`, `masterdata/ItemIT`, `masterdata/ItemMigrationIT`.
- 기존 테스트 정합성: `FoundationIT`, `DepartmentPositionIT`, `HrMigrationIT`, `HrTerminationMigrationIT`, `IamMigrationIT`, `RoleMigrationIT`, `UserRoleMigrationIT`, `CredentialMigrationIT`, `SessionMigrationIT`.
- Evidence: 본 문서, `masterdata-item-unit-run.txt`, `masterdata-item-unit-development-run.txt`.

## 후속 조건 / PM5 리뷰 요청

- 이 결과는 선행 Item/Unit Provider의 로컬 검증이다. PUR-INV-01 전체 Gate A 완료·신규 거래 통합 PASS·현업 공개 승인이 아니다.
- Partner 실제 공급업체 기준정보/적격성/상태 Guard, Warehouse 실제 회사/Site/상태 Guard가 후속 선행 의존성. Site를 Warehouse로 사용하지 않음.
- PO/GR는 NOT_STARTED, T25~36 및 PI-T01~20은 NOT_RUN. 원천 잔량/가격·세금/회계/StockLedger/LOT·검사 제공자 구현 없음.
- Item HTTP/참고가격 노출에는 Action/대상 범위/가격 권한/필수 Audit 승인이 필요. 일반 API default deny 유지.
- 원격 Push·PR는 별도 승인 필요. PM5는 JDBC/CAS 선택, 고정 Unit 카탈로그, 초기 profile 조합, Provider/consumer 경계, V12와 실제 PG 증거를 독립 리뷰해야 한다.
- Gate B NO-GO, 기존 AN/AZ 운영 OPEN·DEFERRED/LIMITATION·구형 writer 혼합 배포 금지를 변경하지 않음.

최종 로그: [full verify](masterdata-item-unit-run.txt). 최종 구현 커밋은 제출 메시지에서 보고하며 위 기준 main SHA를 구현 커밋으로 기재하지 않는다.
