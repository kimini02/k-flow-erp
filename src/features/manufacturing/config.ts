import {
  document,
  master,
  moduleConfig,
  transition,
  col,
  fld,
} from "../../domain/config";
import { ITEMS, WAREHOUSES } from "../../domain/types";
import type { Resource } from "../../domain/types";
export const manufacturingModule = moduleConfig(
  "manufacturing",
  "생산관리",
  "MANUFACTURING",
  [
    ["boms", "BOM", "boms"],
    ["orders", "작업지시", "work-orders"],
    ["issues", "원재료 출고", "material-issues"],
    ["progress", "생산 진행", "work-orders", "생산중"],
    ["receipts", "완제품 입고", "production-receipts"],
    ["overview", "생산현황", "work-orders"],
  ],
);
const workflow = ["BOM", "작업지시", "원재료 출고", "WIP", "완제품 입고"];
export const manufacturingResources: Resource[] = [
  master(
    "boms",
    "manufacturing",
    "BOM",
    "BOM",
    [
      fld("title", "생산 품목", "select", ITEMS),
      fld("version", "버전"),
      fld("outputQty", "기준 생산량", "number", undefined, 1),
      fld("scrap", "로스율 (%)", "number"),
    ],
    [
      col("title", "생산 품목"),
      col("version", "버전"),
      col("outputQty", "기준 생산량", "number"),
      col("scrap", "로스율 (%)", "number"),
      col("amount", "자재원가", "money"),
    ],
    {
      hasLines: true,
      statuses: ["사용중", "비활성"],
      workflow,
      transitions: [
        transition("사용중", "사용중", "작업지시 생성", "work-orders"),
      ],
    },
  ),
  document("work-orders", "manufacturing", "작업지시", "WO", {
    hasLines: false,
    workflow,
    statuses: ["작성중", "확정", "생산중", "생산완료", "취소"],
    fields: [
      fld("title", "생산 품목", "select", ITEMS),
      fld("bom", "BOM 번호"),
      fld("qty", "계획수량", "number", undefined, 1),
      fld("line", "생산라인", "select", [
        "A라인 · 조립",
        "B라인 · 가공",
        "C라인 · 검사",
      ]),
      fld("date", "작업시작일", "date"),
      fld("dueDate", "완료예정일", "date"),
    ],
    columns: [
      col("id", "작업지시번호"),
      col("title", "생산 품목"),
      col("line", "생산라인"),
      col("qty", "계획수량", "number"),
      col("produced", "생산수량", "number"),
      col("progress", "진행률", "progress"),
      col("dueDate", "완료예정일"),
      col("status", "상태", "status"),
    ],
    transitions: [
      transition("작성중", "확정", "작업지시 확정"),
      transition("확정", "생산중", "자재 출고 · 생산 시작", "material-issues"),
      transition("생산중", "생산완료", "생산 완료", "production-receipts"),
      {
        from: ["작성중", "확정"],
        to: "취소",
        label: "작업 취소",
        danger: true,
      },
    ],
    filters: { key: "line", label: "생산라인" },
  }),
  document("material-issues", "manufacturing", "원재료 출고", "MI", {
    workflow,
    hasLines: true,
    initial: "완료",
    readOnly: true,
    statuses: ["완료"],
    columns: [
      col("id", "출고번호"),
      col("title", "생산 품목"),
      col("warehouse", "출고 창고"),
      col("date", "출고일"),
      col("amount", "소모원가", "money"),
      col("status", "상태", "status"),
    ],
  }),
  document("production-receipts", "manufacturing", "완제품 입고", "FG", {
    workflow,
    hasLines: false,
    statuses: ["작성중", "입고완료"],
    fields: [
      fld("title", "완제품", "select", ITEMS),
      fld("qty", "입고수량", "number", undefined, 1),
      fld("warehouse", "입고 창고", "select", WAREHOUSES),
      fld("date", "입고일", "date"),
    ],
    columns: [
      col("id", "입고번호"),
      col("title", "완제품"),
      col("qty", "입고수량", "number"),
      col("warehouse", "입고 창고"),
      col("date", "입고일"),
      col("status", "상태", "status"),
    ],
    transitions: [transition("작성중", "입고완료", "완제품 입고 확정")],
  }),
];
