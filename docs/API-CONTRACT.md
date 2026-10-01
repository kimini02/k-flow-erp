# Spring Boot 연동 계약

현재 `src/api/client.ts`의 `ErpClient` 인터페이스를 Mock와 REST가 함께 구현한다. 페이지는 Query hooks를 호출하고 HTTP 경로를 직접 구성하지 않는다. REST 모드는 계약 예시이며 Spring 서버를 구현/호출한 결과물은 아니다.

이 문서의 요청 경로는 현재 어댑터 구현이며, 아래 서버 불변식과 오류 정규화는 앞으로 구현할 요구사항이다. 현재 코드 구조는 [FRONTEND-ARCHITECTURE.md](FRONTEND-ARCHITECTURE.md), 실제 사용자·DTO·집계 등 추가 전환 작업은 [BACKEND-HANDOFF.md](BACKEND-HANDOFF.md)를 함께 참고한다. 폼과 상세 UI가 Mock store의 검증 함수를 직접 import하는 부분도 있어 모든 계층이 완전히 독립된 상태는 아니다.

## 환경

```env
VITE_API_MODE=rest
VITE_API_URL=http://localhost:8080/api
```

서버는 개발 origin에 대한 CORS와 쿠키 인증을 설정한다. 클라이언트는 `credentials: include`를 사용한다. 실제 사용자 identity는 서버 세션/토큰에서 결정하고, 데모용 actor 요청값을 인증 근거로 사용하지 않는다.

## 요청

|메서드|경로|의미|
|---|---|---|
|GET|/resources/{resource}|검색·필터·정렬·페이지 조회|
|GET|/resources/{resource}/{id}|상세 DTO|
|POST|/resources/{resource}|생성/초안 수정; `{ record, actor }`|
|POST|/resources/{resource}/{id}/transitions|상태 전이; `{ action, actor }`|
|POST|/approvals/{id}/actions|결재 처리; `{ action, actor, reason? }`|

resource key 목록은 `src/app/catalog.ts`와 `features/*/config.ts`. 실제 Spring API가 `/purchasing/purchase-orders` 등으로 구성되면 어댑터에서 key → endpoint map을 정의하면 된다. 현재 생성/수정은 데모 어댑터 단순화를 위해 POST에 통합했으며, 서버 구현 시 POST/PATCH를 분리해 매핑할 수 있다.

목록 query: `search, status, entity, from, to, page(1-based), size, sort, direction(asc|desc), owner, participant(기안자 또는 결재선 참여자)`.

응답:

```ts
interface ListResult {
  rows: RecordData[]; // 필터 및 페이지 적용
  total: number;     // 필터 적용 후 전체 건수
  all: RecordData[]; // 데모 KPI/선택지용 전체 목록
}
```

운영 데이터량이 커지면 `all`을 별도 `/summary`/`/lookup` API로 바꾼다. Mock 단계의 간결함을 위한 계약이지 전체 데이터를 운영 브라우저로 내려보내라는 권고는 아니다. 정확한 DTO는 `src/domain/types.ts`에 정의되어 있다.

## 금액/날짜

데모는 원 단위 안전 정수와 `YYYY-MM-DD`를 사용한다. KRW 단가·수량의 곱은 안전 정수 검증을 거친다. Spring의 BigDecimal을 사용할 때는 소수/통화를 명확히 지정한 문자열 DTO로 바꾸고, 어댑터 또는 공통 Money 계층에서 변환한다. 날짜와 시간은 업무일자와 감사 시각을 분리한다.

## 서버에서 강제할 불변식

- 기안자만 상신/회수, 현재 결재자만 승인/반려. 반려 사유 필수. 정책/결재선은 상신 시 스냅샷.
- 상태별 명령 허용, 중복 요청의 멱등성 및 optimistic locking.
- 문서 생성·후속문서 연결·재고·채권채무·전표·감사 이벤트를 동일 트랜잭션으로 처리.
- 지급/수금은 동일 거래처의 열린 문서만 배분, 양수·잔액 이하, 배분 합계와 지급액 일치.
- 회계 전표는 차변=대변, 전기 후 수정 금지, 역분개 문서 별도 생성, 마감 기간 전기 금지.
- 창고이동은 출발≠도착, 출고 후 가용재고 음수 금지. 다중 행 작업 실패 시 전체 롤백.
- 인증·부서/법인 범위 접근 제한, 서버에서 감사로그 생성, 클라이언트가 audit rows를 생성/수정하지 못하도록 제한.

현재 Mock에도 핵심 규칙을 넣어 사용자 흐름을 시연하지만 브라우저 데이터는 사용자가 수정할 수 있다. 정책/권한의 보안 경계는 서버다.

## 실패와 캐시

읽기 실패는 목록/보고서 Error + retry, 쓰기 실패는 폼/notification으로 노출한다. 성공 후 `['erp']` query를 invalidate해서 연관 목록/KPI/감사로그를 갱신한다. Spring 연동 시 문제 상세 DTO, 401/403/409/422를 `ApiError`로 정규화하고 필요 시 feature 단위 invalidation으로 최적화한다.
