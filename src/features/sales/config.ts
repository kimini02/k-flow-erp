import {
  document,
  moduleConfig,
  transition,
  col,
  fld,
} from "../../domain/config";
import type { Resource } from "../../domain/types";
export const salesModule = moduleConfig("sales", "영업/판매관리", "SALES", [
  ["quotes", "견적", "quotations"],
  ["orders", "판매주문(SO)", "sales-orders"],
  ["deliveries", "출고", "deliveries"],
  ["invoices", "매출", "invoices"],
  ["receivables", "매출채권/AR", "receivables"],
  ["receipts", "수금", "payments-in"],
  ["returns", "반품", "sales-returns"],
]);
const workflow = ["견적", "판매주문", "출고", "매출", "수금", "회계전표"];
export const salesResources: Resource[] = [
  document("quotations", "sales", "견적", "QT", {
    workflow,
    statuses: ["작성중", "확정", "주문전환"],
    transitions: [
      transition("작성중", "확정", "견적 확정"),
      transition("확정", "주문전환", "판매주문 생성", "sales-orders"),
    ],
  }),
  document("sales-orders", "sales", "판매주문", "SO", {
    workflow,
    statuses: ["작성중", "확정", "부분출고", "출고완료"],
    transitions: [
      transition("작성중", "확정", "주문 확정"),
      transition("확정", "부분출고", "출고 문서 생성", "deliveries"),
      transition("부분출고", "출고완료", "출고 완료 처리"),
    ],
  }),
  document("deliveries", "sales", "출고", "DO", {
    workflow,
    statuses: ["작성중", "출고완료"],
    source: "sales-orders",
    transitions: [
      transition("작성중", "출고완료", "출고 확정"),
      transition("출고완료", "출고완료", "매출 문서 생성", "invoices"),
    ],
  }),
  document("invoices", "sales", "매출", "INV", {
    workflow,
    statuses: ["작성중", "전기완료"],
    source: "deliveries",
    transitions: [
      transition("작성중", "전기완료", "매출 전기", "receivables"),
      transition("전기완료", "전기완료", "반품 접수", "sales-returns"),
    ],
  }),
  document("receivables", "sales", "매출채권", "AR", {
    workflow,
    readOnly: true,
    hasLines: false,
    initial: "미수금",
    statuses: ["미수금", "부분수금", "수금완료", "연체"],
    columns: [
      col("id", "채권번호"),
      col("partner", "거래처"),
      col("date", "매출일"),
      col("dueDate", "수금기한"),
      col("amount", "채권액", "money"),
      col("balance", "미수금", "money"),
      col("aging", "경과일", "number"),
      col("status", "상태", "status"),
    ],
  }),
  document("payments-in", "sales", "수금", "RCPT", {
    workflow,
    kind: "payment",
    initial: "수금완료",
    statuses: ["수금완료"],
    hasLines: false,
    source: "receivables",
    fields: [
      fld("title", "수금 적요"),
      fld("partner", "거래처", "select"),
      fld("date", "수금일", "date"),
      fld("bank", "입금 계좌", "select", [
        "기업은행 123-456789-01",
        "국민은행 456-123456-02",
      ]),
      fld("amount", "수금액", "money"),
    ],
    columns: [
      col("id", "수금번호"),
      col("partner", "거래처"),
      col("title", "적요"),
      col("date", "수금일"),
      col("amount", "수금액", "money"),
      col("bank", "입금 계좌"),
      col("status", "상태", "status"),
    ],
  }),
  document("sales-returns", "sales", "반품", "RET", {
    workflow: ["원 매출", "반품 접수", "검수", "반품완료"],
    source: "invoices",
    statuses: ["작성중", "검수중", "완료"],
    transitions: [
      transition("작성중", "검수중", "반품 검수"),
      transition("검수중", "완료", "반품 확정"),
    ],
  }),
];
