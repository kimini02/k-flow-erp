# 참고 저장소 분석 및 K-Flow ERP 재설계

분석일: 2026-09-29. 대상: https://github.com/q86865511/ERPSystem , commit `33b5df13594bcb456ba8a3d20e0bc455ac79aa75`.
실제 Git clone 후 frontend/src/components, features, pages, app, theme.ts, index.css를 조사했다. 아래 경로는 해당 커밋의 frontend/src 기준이다. 구현 코드를 복사하지 않고 구조와 UX 의도를 분석해 새로 작성한다.

## 공통 UI 코드에서 확인한 사항

|파일|실제 구현|정보를 보여주는 이유 / K-Flow 적용|
|---|---|---|
|components/DataTable.tsx|제네릭 columns/rows, render, searchValue, 정렬 3단계, 내부 검색, 25행 기본 pagination, skeleton, empty CTA, row click + 키보드 접근 가능한 chevron|문서 비교·탐색 비용 감소. K-Flow는 검색·상태·거래처·기간을 API ListParams에 통일하고 10행 단위 pagination|
|components/DetailDrawer.tsx|Mantine 우측 xl Drawer, title/children/footer, focus trap/return focus|목록 맥락 유지. 기본정보·품목·진행흐름·관련문서·이력과 상태 액션을 공통화|
|components/EntitySelect.tsx|품목/거래처/창고/위치 query hooks, searchable Select, numeric ID ↔ string 변환, vendor/customer/itemType 제한|외래키를 사용자가 이해할 명칭으로 선택. K-Flow도 검색 가능한 EntitySelect와 masterdata API 분리|
|components/AmountAllocationTable.tsx|미결제 문서별 배분 입력, openBalance 안내, BigInt 합산|지급액과 여러 채무 상계의 관계를 표현. 지급·수금 폼에 잔액 및 배분 합계 검증|
|components/Money.tsx|금액 문자열 formatting, scale 4 BigInt sum, tabular nums|부동소수점 손실 방지. K-Flow 데모는 안전 정수 범위 KRW 원 단위, 서버 계약에서 decimal string 전환 명시|
|components/EmptyState.tsx|message + 선택적 CTA|빈 상태와 데이터 존재 상태 구분. K-Flow는 오류 Alert/재시도도 공통화|
|components/StatusBadge.tsx, SealBadge.tsx|상태-색 매핑, APPROVED/POSTED 등 stamp 스타일|검토·전기 상태와 진행상태 구분. 원본 인장 디자인은 복제하지 않고 텍스트+색+점 Badge|
|components/StateButton.tsx|disabled 사유 tooltip, mutation loading|불가능한 전이를 설명. 상태 전이 API에서도 동일 제약 검증|
|components/StatTile.tsx, charts/KpiTile.tsx|금액/숫자 모드, 아이콘, 증감률, 조건부 색|업무량·위험 항목 우선 파악. K-Flow도 목록 기반 KPI를 계산|
|components/AppLayout.tsx|AppShell, 역할별 navigation, nested nav → ?tab=, lazy Outlet, AI Drawer 최초 활성화 후 유지|업무 모듈 이동과 대화 맥락 유지. K-Flow는 /module/screen 및 ?doc= 상세 링크|
|pages/DashboardPage.tsx|매출·순이익·주문·AR·재고, 주문 funnel, 재고 donut, 부족재고/연체, 매출 추이, ReconciliationHero|요약에서 업무 예외를 찾고 모듈로 이동. K-Flow는 결재 할 일과 한국형 업무 흐름을 강조|
|app/router.tsx, queryClient.ts|lazy module routes, 인증·역할 guards, print 전용 routes, query cache|레이아웃 지속성·기능 분리. 데모의 사용자 전환은 인증이 아니라 결재 역할 시연 도구|
|theme.ts, index.css|Ink Ledger: ink teal, warm paper, serif Chinese titles, self-host fonts, light/dark tokens, scoped sidebar variables|시각 언어는 새로 설계: cool gray + dark navy + green, 한국어 sans, 절제된 border와 밀도|

## 모듈별 실제 화면/동작 조사

### 구매 purchasing
PurchasingPage는 orders/receipts/bills/payments/ap-aging 탭. PurchaseOrdersPanel 컬럼은 발주번호·거래처·발주일·상태; 상세는 발주/입고/청구 수량과 단가, 작성중 + 권한일 때 확정. 생성 Modal은 거래처·일자·가변 품목행. GoodsReceiptsPanel은 PO를 선택하고 잔여 수량·위치·posting date로 입고. VendorBillsPanel은 PO 기반 청구, 공급가액·VAT·총액·미결제 잔액 및 전표 ID를 노출. ApAgingPanel은 기준일 + 현재/1–30/31–60/61–90/90일 초과 구간. 상단 KPI와 검색은 모든 구매 탭에 일괄 적용되어 있지는 않다.

### 재고 inventory / 기준정보 masterdata
InventoryPage는 dashboard/overview/adjustments. 대시보드에는 재고 평가액·재주문 수·공급사 납기, category donut/treemap. overview는 품목·위치 선택 후 보유량/평균단가/평가액과 원장 대사. AdjustmentsPanel은 증감수량·단가·사유·posting date 입력, 성공 후 전표 표시. MasterDataPage는 품목/거래처/창고/위치 탭; 품목은 SKU·유형·단위·재고관리 여부·표준원가, 거래처는 공급/고객 역할·세금번호·결제조건, 위치는 창고 필터. 원본 일부 기준정보는 생성 Modal만 있고 행 상세가 없는 점을 K-Flow에서 보완.

### 판매 sales
orders/deliveries/invoices/receipts/returns/ar-aging. SO 컬럼은 번호·고객·일자·상태, 검색/정렬 지원. 작성중 확정, SO 기반 출고·청구. Invoice는 총액·COGS·미수잔액을 함께 보여 수익과 회수 상황 구분. Delivery/Invoice에는 인쇄 이동. 반품은 POSTED invoice 선택으로 원 문서 근거 유지. 상세 품목에는 수량·금액·원가·관련 전표. AR aging은 AP와 동일 기준일 버킷.

### 제조 manufacturing
dashboard/work-orders/boms/reorder. BOM은 모품목·버전·산출량·상태; 생성 시 component item/qty per/scrap pct. WorkOrdersPanel은 계획량·생산량·상태와 부품 소모 비용. DRAFT→RELEASED→IN_PROGRESS→COMPLETED 상태에 따라 release/issue/complete 버튼 활성화; action Modal은 창고 위치·posting date·실적 수량. dashboard는 WIP·달성률·생산량, Gantt 및 downtime. reorder는 재고량·재주문점·추천 발주량. 재료 투입과 생산 실적이 단순 CRUD로 표현되지 않는 이유를 재사용.

### 회계 ledger / 보고 reporting
LedgerPage는 manual-entry/reversal/periods. 수기 전표는 계정·차변·대변 행, 역분개는 전표 ID와 posting date, 기간은 월/연도 마감·재개 및 상태/연말 전표 결과. ReportsPage는 기준일을 공유하는 overview/trial-balance/income-statement/balance-sheet. TrialBalance에서 계정을 눌러 GeneralLedgerDrawer로 drill-down; 원장에는 일자·전표·원천문서·적요·차대변. 대시보드 ReconciliationHero는 GL과 보조원장 대사를 표시. K-Flow는 회계 메뉴로 원장/재무제표를 통합하고 차대변 검증을 데모에도 적용.

### 인사 hr
dashboard/employees/departments/positions/attendance/leave/timesheets/payroll. 직원은 사번·이름·부서·직급·급여·상태, 부서/직급 생성 폼. 근태는 직원 필터·일자·상태·시간·비고. 휴가는 상태 필터·기간·일수·결정자, PENDING만 승인/반려. Timesheet는 DRAFT 상신, SUBMITTED 승인. 급여는 월·총액·세금·보험·실지급액·전표, DRAFT만 전기; Drawer는 직원별 급여명세. 한국형 별도 결재선은 K-Flow에서 새로 추가.

### 지급/수금 payments
PaymentsOutPanel/PaymentsInPanel은 구매/판매 탭에 통합. 거래처 선택 후 미결제 bill/invoice별 배분, posting date, 현금 계정; 상세는 금액·상태·배분과 전표. 미결제 문서별 소거 관계가 핵심이며 K-Flow도 같은 거래처/잔액 이하 검증.

### 감사 audit
AuditPage는 이벤트 타입·수행자 debounced 필터와 서버 pagination. 일시·이벤트·수행자·요약·참조 문서. ADMIN route guard. 읽기 전용이며 일반 CRUD 생성 버튼이 없다. K-Flow mutation은 감사로그를 자동으로 남기고 이벤트에서 실제 대상 문서로 이동.

### AI assistant
AssistantDrawer는 우측 채팅, 오류/429 표시, 대화 상태 유지. SSE/reducer/useAssistantChat가 UI에서 분리. ToolCard는 읽기 running→success/error와 쓰기 proposed→확인/거절→running→success/error를 구분, 중복 클릭 방지. K-Flow는 외부 AI API 호출 없이 Mock 응답과 명시적 쓰기 확인 카드만 구현.

## 공통 상태 처리 차이
원본 DataTable의 loading/empty 처리는 풍부하지만 각 panel의 list-query 오류는 일관된 독립 오류 뷰가 아니다. mutation은 notifyError를 사용. 날짜/상태/검색은 기능에 따라 있고 전체 공통 강제가 아니다. K-Flow는 모든 목록에 검색·상태·관련 엔티티·기간·정렬·pagination·loading/empty/error/retry를 통일한다.

## K-Flow 화면/설계
전체 메뉴는 요청서 11개 영역과 모든 하위 화면을 유지한다. src/app/catalog.ts가 모듈 navigation, features/*/config.ts가 실제 컬럼·폼·전이·데이터 종류를 정의한다. DocumentListPage는 공통 목록/필터를, DocumentDetails는 정보/품목/흐름/연결/이력을 제공한다. 결재·회계보고·AI는 전용 화면/패널로 분리한다. 기준정보는 재고/인사에 통합하고 지급·수금은 구매/판매에 둔다.

전자결재는 기안자·현재 결재자 식별, 작성중/상신/결재중/승인완료/반려/회수 상태, 결재선 단계, 반려사유, ERP 원천문서 링크를 신규 구현한다. 후속 ERP 문서는 명시적 버튼으로 생성하고 원본/생성 문서를 양방향 연결한다.

## 라이선스
원본 MIT (Copyright (c) 2026 q86865511). 원본 소스와 자산은 제품 소스에 복사하지 않는다. 출처와 원본 라이선스 전문을 docs/REFERENCE-LICENSE.txt에 함께 보관한다. 의존성 라이선스는 각각의 배포 조건을 따른다.
