# K-Flow ERP

[개발 문서 기준 및 읽는 순서 — PROJECT-INDEX.md](PROJECT-INDEX.md)

한국 제조·유통 중견기업을 위한 **React + TypeScript ERP 프론트엔드**입니다. 전자결재와 구매·판매·재고·생산·회계·인사 업무를 연결하는 시연용 프로젝트입니다.

## 개발 설명과 다음 단계

- [프론트엔드 구현·아키텍처](docs/FRONTEND-ARCHITECTURE.md): 기술 구성, 공통 화면 설계, 상태 관리, 데이터 흐름과 현재 한계.
- [백엔드 인수인계](docs/BACKEND-HANDOFF.md): Spring Boot 연결 범위, ERD 설계 출발점, adapter 변경 사항, 다른 GPT에 전달할 메시지.
- [포트폴리오 설명 초안](docs/PORTFOLIO-NOTES.md): 프로젝트 소개, 설계 결정, 시연 순서, 면접 설명과 제작 역할.

## 실행

Node.js 22 이상 권장. 이 환경에서는 Node.js 24로 검증했습니다.

```bash
npm ci
npm run dev
```

[로컬 화면 열기](http://127.0.0.1:5173/)

```bash
npm run build       # TypeScript + Vite production build
npm run preview     # production build 미리보기
npm test            # 상태 전이·금액·재고·문서 연결 검증
npm run test:e2e     # dev 서버 실행 후 브라우저 테스트
```

브라우저 테스트는 이 Mac의 Chrome 실행 경로를 기본값으로 사용합니다. 다른 환경에서는 `playwright.config.ts`의 `executablePath`를 수정하거나 설치된 Playwright Chromium을 사용하도록 설정하세요.

## 구현 범위

**54개 경로 화면 + 전역 AI 우측 패널**, 36종의 업무 자료. 모든 요청 메뉴에 조회 가능한 Mock 데이터가 있습니다.

|영역|화면|
|---|---|
|대시보드|KPI, 매출/매입 추이, 결재, 업무 체크리스트, 바로가기, 구매 흐름|
|전자결재|내 결재함, 기안문서, 결재대기, 결재완료, 반려문서, 결재선 관리, 결재 정책 관리|
|구매관리|구매요청(PR), 구매발주(PO), 입고, 매입, 매입채무/AP, 지급|
|재고관리|재고현황, 품목, 창고, 수불부, 창고이동, 재고조정, 안전재고/부족재고|
|영업/판매|견적, 판매주문(SO), 출고, 매출, 매출채권/AR, 수금, 반품|
|생산관리|BOM, 작업지시, 원재료 출고, 생산 진행, 완제품 입고, 생산현황|
|회계관리|계정과목, 전표, 총계정원장, 시산표, 손익계산서, 재무상태표, 회계기간, 역분개|
|인사관리|직원, 부서, 직급, 근태, 연차, 급여|
|보고서/분석|경영, 구매, 매출, 재고, 생산 분석|
|감사로그|이벤트 검색/필터, 변경 전후 상세, 원천문서 이동|
|AI Assistant|5종 Mock 질문, 근거 문서 링크, 초안 확인·취소·생성|

목록 공통: 검색, 상태/엔티티/기간 필터, 정렬, pagination, CSV 내보내기, loading skeleton, empty, error/retry. 업무 문서는 상세 Drawer, 신규/수정 Modal, 상태별 액션, 관련 문서, 처리 이력을 제공합니다. 원장·분석·감사 자료는 조회 전용입니다.

## 5분 시연

1. **대시보드** → KPI, 결재 진행 문서, 구매 흐름 확인.
2. **구매요청 → 신규** → 품목/수량/단가 입력 → 저장 → 결재 상신. 관련 결재 문서로 이동합니다.
3. Drawer를 닫고 상단 **시연 사용자**를 현재 결재자로 변경합니다. 결재선을 따라 승인합니다. 기안자만 회수, 현재 결재자만 승인/반려할 수 있습니다.
4. 최종 승인된 구매요청에서 **발주 생성 → 발주 확정 → 입고 문서 생성 → 입고 확정 → 매입 문서 생성 → 매입 전기**를 진행합니다.
5. **지급 → 신규**에서 같은 거래처의 채무에 금액을 배분합니다. 저장하면 미지급 잔액이 감소하고 연결된 전표가 만들어집니다.
6. **회계 → 시산표**에서 차대변 합계, 계정 클릭 → 총계정원장 → 전표 상세를 확인합니다.
7. **AI Assistant**에 “ABC전자 맥북 10대 구매요청 초안 만들어줘”를 입력합니다. 확인 전에는 생성되지 않습니다. 생성하면 작성중 구매요청으로 연결됩니다.
8. **감사로그**에서 방금 처리한 작업과 원천문서를 확인합니다.

생산은 BOM에서 작업지시를 생성한 뒤 계획수량을 가용재고에 맞게 수정하고 확정하세요. 자재 출고 시 부족재고를 검증합니다. 데모 시드의 완성된 문서들은 목록/상세 탐색용이며, 새 문서로 순차 처리하면 상태 전이를 가장 명확히 확인할 수 있습니다.

## 구조

```text
src/
  app/          # 공통 셸, 메뉴/리소스 등록, QueryClient
  components/   # DataTable, DetailDrawer, FilterBar, DocumentForm 등
  pages/        # Dashboard, 공통 업무 목록
  features/
    approval/ purchasing/ inventory/ sales/ manufacturing/
    accounting/ hr/ reporting/ audit/ assistant/ payments/
  api/          # Mock/REST adapter, Query hooks, 브라우저 저장소, seed
  domain/       # types, resource config helpers, 검증/거래 효과, CSV
  theme.ts      # Mantine theme
  index.css     # 디자인 토큰, 화면 레이아웃, 반응형
```

React 19, TypeScript 5, Vite 7, Mantine 8, TanStack Query 5, React Router 7, Recharts 3. 원본의 UX를 분석하고 **새 코드로 작성**했습니다. 분석 기준 커밋 및 파일별 근거는 [REFERENCE-ANALYSIS.md](docs/REFERENCE-ANALYSIS.md)에 있습니다.

## Mock 저장소와 REST 전환

기본값은 Mock입니다. 외부 AI/백엔드 API 호출이나 인증 키가 필요하지 않습니다. 비동기 API 응답과 Query invalidation을 사용하고, 변경사항을 이 브라우저의 `localStorage`(`k-flow-erp-v1`)에 저장합니다. 사용자 전환은 결재 권한 시연 기능입니다.

초기화: 개발자 도구에서 `localStorage.removeItem('k-flow-erp-v1')` 후 새로고침.

서버 연결 시 `.env.local`에 `VITE_API_MODE=rest`와 `VITE_API_URL`을 지정하고 [API-CONTRACT.md](docs/API-CONTRACT.md)의 계약을 검토합니다. 엔드포인트와 DTO는 `src/api/client.ts`의 REST adapter에서 매핑하고, 실제 사용자·권한·기준정보·보고서 집계·오류 처리도 서버 계약에 맞춰 연결해야 합니다. 환경변수만으로 운영 연동이 완료되지는 않으며 구체적인 전환 대상은 [BACKEND-HANDOFF.md](docs/BACKEND-HANDOFF.md)에 정리했습니다.

## 시연 범위와 경계

- 기준일은 2026-09-29입니다. 월별 매출 추이는 **고정 분석 시나리오**로 명시되어 있고, 목록·결재·재고·회계 보고서는 Mock 저장소를 조회합니다.
- 초기 데이터는 탐색용 합성 샘플입니다. 전체 회사의 실제 200–500명 데이터, 세금신고, 법정 급여 계산, 다단계 BOM/MRP, 복수 회사 회계, 전자세금계산서는 포함하지 않습니다.
- 회계 보고서는 전기된 데모 전표를 합산합니다. 부가세는 입력 화면의 안내용 계산이며 세금 계정까지 전기하는 완전한 한국 세무 엔진은 아닙니다. 재무상태표는 2026년 기초부터 기준일까지의 전표를 기준으로 합니다.
- Mock는 프론트엔드 시연을 위한 단일 브라우저 저장소입니다. 서버 트랜잭션/동시성/실제 인증·인가/승인 정책의 강제는 Spring Boot에서 구현해야 합니다.
- AI는 정해진 질문 유형을 처리하는 Mock이며, 제안된 쓰기 작업은 확인 카드의 생성 버튼을 누른 경우에만 실행됩니다.

## 참고 및 라이선스

참고: [q86865511/ERPSystem](https://github.com/q86865511/ERPSystem), MIT, Copyright (c) 2026 q86865511. 원본 소스/폰트/이미지를 제품에 복사하지 않았습니다. 출처 추적을 위해 원본 MIT 고지를 [REFERENCE-LICENSE.txt](docs/REFERENCE-LICENSE.txt)에 보관합니다.

검증 내역: [VALIDATION.md](docs/VALIDATION.md). 화면 캡처: `docs/screenshots/`.
