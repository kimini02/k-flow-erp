# K-Flow ERP Backend Foundation

Java 21 / Spring Boot 4.1.1의 Walking Skeleton이다. ERP 업무, 인증, API 계약 구현은 아직 없다. 기존 React/Vite는 저장소 루트에 그대로 둔다.

## 실행

JDK 21, Docker Engine 또는 실행 중인 Docker Desktop, 초기 의존성 다운로드를 위한 네트워크가 필요하다. Maven 별도 설치는 필요 없다. Wrapper는 Maven 3.9.11을 사용한다.

저장소 루트에서:

```bash
cd backend
java -version
./mvnw -version
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Mac에서 기본 Java가 21이 아니라면 현재 터미널에만 적용한다.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

- 앱: `http://localhost:8080` (업무 루트 페이지는 없으므로 `/`의 404는 정상)
- OpenAPI: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- PostgreSQL: `localhost:5433`, PostgreSQL 16
- 로컬 기본 DB/user/password: `kflow` / `kflow_local` / `kflow_local_only`. 개발 전용이며 운영 비밀이 아니다.
- 앱과 DB는 로컬 프로필/Compose에서 loopback 주소에 바인딩한다. 인증은 구현하지 않았다.

`DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_PORT`를 같은 터미널에서 export하면 Compose와 앱에 함께 적용된다. `DB_URL`은 앱 JDBC URL 전체, `SERVER_PORT`는 앱 포트를 재정의한다. Compose의 `.env`는 Maven 프로세스에 자동 전달되지 않는다. 기존 볼륨의 DB 계정은 환경변수 변경만으로 변경되지 않는다.

이미 `postgres:16` 이미지가 있는데 Docker credential helper에서 pull이 멈춘 경우에는 `docker compose up -d --pull never postgres`로 캐시된 동일 이미지를 사용할 수 있다. 이미지가 없으면 이 명령은 실패하므로 최초 다운로드를 대신하지 않는다.

종료는 앱의 Ctrl+C 후 `docker compose down`을 사용한다. DB 볼륨은 보존한다. 로컬 데이터를 의도적으로 삭제할 때만 `docker compose down -v`를 사용한다.

## 설정과 DB

- `application.yml`: Hibernate `validate`, OSIV off, Flyway enabled, 명시적으로 표시한 Modulith 모듈만 감지.
- `application-local.yml`: 개발용 PostgreSQL 연결 및 loopback HTTP 설정. 기본 프로필을 local로 강제하지 않는다.
- `db/migration/.gitkeep`: 위치만 보존한다. SQL migration과 ERP table은 없다. Flyway가 초기화 중 만드는 자체 이력 테이블은 업무 테이블이 아니다.
- 첫 업무 migration 번호를 소비하지 않는다. 환경에 따라 Flyway의 empty migration 경고가 발생할 수 있다.

## 검증

`backend/`에서 실행한다.

```bash
./mvnw clean test
./mvnw verify
```

- Surefire: `*Test` / `*Tests`, Docker 없이 실행하는 JUnit·Modulith·ArchUnit·Framework 오류 응답 테스트.
- Failsafe: `*IT`, `verify`에서 실제 PostgreSQL 16 Testcontainer와 앱 HTTP 서버를 실행한다.
- Testcontainers는 Compose DB와 별개로 임시 DB를 만든다. Docker가 없으면 skip하지 않고 실패한다. H2 대체도 없다.
- Integration Test는 PostgreSQL 제품/버전, Flyway 상태, JPA validate, 업무 Entity·table 0개, OpenAPI와 Swagger UI를 확인한다.
- 테스트 결과는 `target/surefire-reports/`, `target/failsafe-reports/`에 생성한다.
- GitHub workflow는 JDK 21에서 `./mvnw -B -ntp verify`를 실행한다. 로컬 성공이 원격 CI 실행 성공을 뜻하지 않는다.

## 모듈 경계

`com.kflow.erp` 아래 14개 모듈을 둔다.

```text
organization iam masterdata approval purchasing inventory accounting
payments sales manufacturing hr reporting audit assistant
```

각 모듈은 `api`, `application`, `domain`, `infrastructure`, `web` 패키지를 가진다. root의 `@ApplicationModule`과 api의 `@NamedInterface("api")`만 선언한다. 실제 Port·Entity·Repository·업무 Service·Controller는 없다.

Spring Modulith가 실제 14개 감지 여부 및 `verify()`로 모듈 순환/내부 접근을 검사한다. `technical.web`은 업무 모듈이 아니다. 공개 계약은 `<module>.api`에 두고 module root에는 업무 클래스를 두지 않는다.

ArchUnit은 production class만 검사한다. domain→application/infrastructure/web, application→infrastructure/web, api→내부 구현 계층 의존을 금지한다. 현재 비어 있는 계층은 허용하되 새 클래스는 자동 검사한다. 따라서 현재 성공은 향후 업무 정합성을 증명하는 결과가 아니다.

## 오류 응답

`technical.web.FrameworkProblemHandler`는 Spring MVC가 처리하는 요청/검증 오류의 ProblemDetail에 code를 추가한다. 400은 `INVALID_REQUEST`, 나머지 framework 4xx는 `REQUEST_REJECTED`, framework 5xx는 `REQUEST_PROCESSING_FAILED`다. 검증 오류의 `errors`에는 field/reason만 담고 거부된 원문 값은 포함하지 않는다.

`IllegalArgumentException`, `IllegalStateException`을 전역적으로 400/409에 대응시키지 않는다. Domain 예외 체계나 모든 서버 오류의 표준화는 이번 범위가 아니다. 오류 테스트의 Controller는 `src/test`에만 존재하며 제품 OpenAPI에는 포함되지 않는다.

## 참고 및 선택

[q86865511/ERPSystem](https://github.com/q86865511/ERPSystem/tree/33b5df13594bcb456ba8a3d20e0bc455ac79aa75)의 ADR 0001/0003, ArchitectureTest, pom, CI를 읽었다. 조사 시 원격 main도 위 commit이었다.

- 모듈별 package, 공개 Port, Wrapper, Flyway, 실제 PostgreSQL 검증 방향을 참고했다. 코드를 복사하지 않았다.
- 원본의 module별 ArchUnit 조합 대신 Modulith는 module graph, ArchUnit은 내부 layer를 담당한다.
- 원본의 ledger shared kernel, 재고 평가/락 순서/동기 전기 규칙은 가져오지 않았다. K-Flow의 accounting은 소유 모듈이며 업무별 transaction/lock/idempotency는 후속 결정이다.
- 고정 버전은 사용자 지시대로 Boot 4.1.1, Modulith BOM 2.1.1, springdoc 3.1.1, ArchUnit core 1.5.0. Testcontainers/JUnit은 Boot dependency management를 따른다.

확인한 공식 자료: [Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [Modulith 구조/감지](https://docs.spring.io/spring-modulith/reference/fundamentals.html), [Boot Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html), [springdoc](https://springdoc.org/).
