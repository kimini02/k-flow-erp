# Backend Foundation — 실제 검증 기록

- 검증일: 2026-10-01
- 범위: Walking Skeleton. 업무 동작, 인증, 트랜잭션/락/멱등성 검증이 아니다.
- 작업 브랜치: `codex/backend-foundation` (시작점: 문서 기준 커밋 `173f467`)
- 기존 root Frontend와 기준 문서는 이동·수정하지 않았다. 작업 전 파일 해시와 종료 시 비교했다.

## 시작 상태

React/TypeScript/Vite가 저장소 root의 `src/`, `package.json`에 있었다. Backend/Java/pom/Gradle, `.github/workflows`, Docker/Compose 파일은 없었다. 기존 Git에는 문서만 기록되어 있고 Frontend와 루트 `.gitignore` 등은 이미 미추적 상태였다. 기존 미추적 파일을 이번 작업 결과로 취급하지 않는다.

기본 `java -version`은 22.0.2였다. 설치된 JDK 21을 현재 실행 프로세스에서 선택했다. Docker Desktop은 처음에는 daemon에 연결할 수 없었으나 실행 후 27.4.0으로 연결됐다.

## 실제 실행한 명령과 결과

아래 Maven/Compose 명령의 작업 디렉터리는 `backend/`다. 이 Mac에서는 실행 전에 아래 환경을 선택했다.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./mvnw -version
./mvnw -B -ntp clean test
./mvnw -B -ntp verify
docker compose config --quiet
docker compose up -d postgres
docker compose up -d --pull never postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

| 검증 | 실제 결과 |
| --- | --- |
| Java / Wrapper | Java 21.0.2, Maven 3.9.11 |
| Parent | `spring-boot-starter-parent:4.1.1`; 빌드 로그 repackage 4.1.1 및 앱 기동 확인 |
| 최종 clean test | PASS, 7 tests / 0 failures / 0 errors / 0 skipped |
| Modulith | 14개 module 정확히 감지, `ApplicationModules.of(KFlowErpApplication.class).verify()` PASS |
| ArchUnit | domain/application/api 계층 규칙 3개 PASS; 업무 클래스는 아직 비어 있음 |
| Framework 오류 | 잘못된 JSON, 필드 검증, 프로그래밍 예외의 400 오분류 방지 3개 PASS |
| verify | PASS, 빠른 테스트 7개 + Integration Test 2개, skip 없음 |
| Testcontainers | 실제 Docker에 연결, PostgreSQL 16 이미지 pull 및 컨테이너 시작. PostgreSQL 16.15 사용 |
| DB/Context | 실제 PostgreSQL 연결, Flyway 초기화/validate, JPA EntityManagerFactory 시작, Entity 0개, 업무 table 0개 |
| OpenAPI/Swagger IT | 실제 HTTP 서버에서 API spec과 Swagger HTML 성공, 업무 paths 비어 있음 |
| Compose config | PASS |
| 기본 Compose up | credential helper에서 대기하여 종료. 성공으로 기록하지 않음 |
| 캐시 이미지로 Compose up | PASS, `127.0.0.1:5433` PostgreSQL 기동 |
| local profile 실행 | PASS, localhost 8080에서 기동 |
| GitHub Actions | workflow 작성만 완료. 원격 CI 실행은 하지 않음 |

로컬 HTTP/DB 확인에 실행한 명령:

```bash
curl --fail --silent --show-error http://127.0.0.1:8080/v3/api-docs -o /private/tmp/kflow-foundation-tools/openapi.json
curl --fail --silent --show-error --location http://127.0.0.1:8080/swagger-ui.html -o /private/tmp/kflow-foundation-tools/swagger.html
docker compose exec -T postgres psql -U kflow_local -d kflow -c 'select version();' -c "select tablename from pg_tables where schemaname='public';"
```

별도 Python assertion으로 spec의 `openapi=3.1.0`, `paths={}`, Swagger HTML의 `Swagger UI`를 확인했다. SQL에서 public table은 `flyway_schema_history` 하나였다. `No migrations found` 경고는 SQL migration을 아직 만들지 않은 이번 범위와 일치하며 Flyway를 비활성화하지 않았다.

## 실제 발생한 문제와 수정

### 1. 테스트 포트 annotation 의존성

- 최초 `clean test`: `package org.springframework.boot.web.server.test does not exist`, `cannot find symbol LocalServerPort`로 testCompile 실패.
- 원인: 선택한 최소 test 의존성 집합에 해당 annotation이 없음.
- 후보: 관련 web test 모듈 추가 / 이미 제공되는 실제 런타임 port property 사용.
- 선택: `@Value("${local.server.port}")`로 실제 서버 포트 주입. 불필요한 테스트 dependency를 추가하지 않았다.
- 재검증: testCompile 통과, 실제 HTTP Integration Test 통과.

### 2. ProblemDetail type 필드 누락

- 초기 오류 응답 테스트: `No value at JSON path "$.type"` 실패.
- 관찰: 기본 `about:blank` 유형이 응답 JSON에서 생략됐다. 예외 발생 여부만 확인했다면 계약 차이를 놓쳤을 상황이다.
- 후보: 필드 부재 허용 / 명시적인 Foundation 오류 URI 지정.
- 선택: `urn:kflow:problem:invalid-request` 등 code에 대응하는 URI 지정. 기대 응답의 type 필드를 유지한다.
- 재검증: 최종 오류 응답 테스트 3개 통과. 무조건적인 IllegalArgumentException→400 매핑은 추가하지 않았다.

### 3. 로컬 Docker credential helper 대기

- `docker compose up -d postgres`가 `postgres Pulling`에서 대기했다.
- 확인: 이번 Compose의 자식 프로세스 `docker-credential-desktop get`이 대기 중이었고, `docker image inspect postgres:16`은 성공했다. 자격 증명 도우미 내부 원인은 확정하지 않았다.
- 선택: 해당 실행만 종료하고 `--pull never`로 이미 Testcontainers가 받은 동일 PostgreSQL 이미지를 사용했다. 사용자 Docker 인증 설정과 다른 컨테이너는 변경하지 않았다.
- 결과: Compose 및 local Boot 실행 성공. 일반 pull 경로 자체가 해결됐다고 주장하지 않는다.

### 도구 환경에서 관찰한 별도 문제

Python urllib의 Maven Central 확인은 로컬 CA 인증 오류로 실패했다. TLS 검증을 끄지 않고 정상 동작한 curl로 지정 artifact 4개가 HTTP 200인 것을 확인했다. 실제 Maven Wrapper 의존성 해석/컴파일은 성공했다. 이는 Backend 런타임 장애가 아니다.

## 검증의 한계와 후속 범위

- Modulith/ArchUnit 성공은 현재 metadata와 기술 코드에 대한 결과다. 업무 클래스가 없어 실제 업무 협력, 트랜잭션, 동시성, 권한은 검증하지 않았다.
- 테스트용 RequestFixture는 `src/test`에만 있다. 패키징된 앱에는 업무/가짜 API Controller가 없다. Integration Test와 별도 local HTTP에서 `paths={}`를 확인했다.
- IAM/JWT, Entity, Repository, 업무 Service/Controller, Cross-module Use Case, Lock, Idempotency, 재고/회계 Posting은 의도적으로 미구현이다.
- 기준 문서의 OPEN/DRAFT 업무 정책은 변경하지 않았다.
- Maven 상세 로그 및 Surefire/Failsafe XML은 로컬 실행 산출물이며 Git에 넣지 않는다. 이 문서는 실제 결과 요약이다.

## 변경 파일과 목적

- `backend/.gitignore` — Backend 산출물과 로컬 설정 제외
- `backend/.mvn/wrapper/maven-wrapper.properties` — Wrapper 3.3.4 / Maven 3.9.11 고정
- `backend/README.md` — 실제 실행·검증 절차와 설계 범위
- `backend/compose.yml` — 로컬 PostgreSQL 16만 실행
- `backend/mvnw` — 공식 Apache Maven Wrapper 실행 스크립트
- `backend/mvnw.cmd` — 공식 Apache Maven Wrapper Windows 스크립트
- `backend/pom.xml` — 고정 버전 의존성과 Maven 검증 lifecycle
- `backend/src/main/java/com/kflow/erp/KFlowErpApplication.java` — Spring Boot 실행 진입점
- `backend/src/main/java/com/kflow/erp/accounting/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/accounting/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/accounting/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/accounting/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/accounting/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/accounting/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/approval/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/approval/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/approval/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/approval/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/approval/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/approval/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/assistant/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/assistant/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/assistant/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/assistant/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/assistant/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/assistant/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/audit/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/audit/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/audit/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/audit/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/audit/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/audit/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/hr/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/hr/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/hr/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/hr/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/hr/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/hr/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/iam/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/iam/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/iam/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/iam/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/iam/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/iam/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/inventory/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/inventory/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/inventory/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/inventory/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/inventory/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/inventory/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/manufacturing/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/manufacturing/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/manufacturing/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/manufacturing/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/manufacturing/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/manufacturing/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/masterdata/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/masterdata/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/masterdata/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/masterdata/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/masterdata/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/masterdata/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/organization/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/organization/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/organization/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/organization/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/organization/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/organization/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/payments/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/payments/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/payments/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/payments/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/payments/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/payments/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/purchasing/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/purchasing/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/purchasing/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/purchasing/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/purchasing/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/purchasing/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/reporting/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/reporting/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/reporting/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/reporting/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/reporting/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/reporting/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/sales/api/package-info.java` — 향후 Published Contract 위치를 NamedInterface로 선언
- `backend/src/main/java/com/kflow/erp/sales/application/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/sales/domain/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/sales/infrastructure/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/sales/package-info.java` — 업무 module 명시적 감지 metadata
- `backend/src/main/java/com/kflow/erp/sales/web/package-info.java` — 업무 클래스 없이 내부 계층 위치 예약
- `backend/src/main/java/com/kflow/erp/technical/web/FrameworkProblemHandler.java` — Framework ProblemDetail 응답
- `backend/src/main/resources/application-local.yml` — 로컬 연결과 환경변수 override
- `backend/src/main/resources/application.yml` — Flyway/JPA/OSIV/Modulith 공통 설정
- `backend/src/main/resources/db/migration/.gitkeep` — SQL 없이 migration 위치 보존
- `backend/src/test/java/com/kflow/erp/FoundationIT.java` — 실제 PostgreSQL/Flyway/JPA/HTTP 통합 검증
- `backend/src/test/java/com/kflow/erp/LayerArchitectureTest.java` — ArchUnit 내부 layer 경계
- `backend/src/test/java/com/kflow/erp/ModuleArchitectureTest.java` — 14개 module 감지와 Modulith verify
- `backend/src/test/java/com/kflow/erp/technical/web/FrameworkProblemHandlerTest.java` — 실패 응답 및 예외 오분류 검증
- `.github/workflows/backend.yml` — JDK 21 및 Maven verify CI
- `docs/test-evidence/backend-foundation.md` — 실제 검증과 실패·수정 경과
