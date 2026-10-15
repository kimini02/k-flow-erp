import {
  document,
  master,
  moduleConfig,
  transition,
  col,
  fld,
} from "../../domain/config";
import type { Resource } from "../../domain/types";
export const accountingModule = moduleConfig(
  "accounting",
  "회계관리",
  "ACCOUNTING",
  [
    ["accounts", "계정과목", "accounts"],
    ["journals", "전표", "journals"],
    ["ledger", "총계정원장", "journals"],
    ["trial-balance", "시산표", "journals"],
    ["income", "손익계산서", "journals"],
    ["balance-sheet", "재무상태표", "journals"],
    ["periods", "회계기간", "fiscal-periods"],
    ["reversals", "역분개", "journals"],
  ],
);
export const accountingResources: Resource[] = [
  master(
    "accounts",
    "accounting",
    "계정과목",
    "ACC",
    [
      fld("title", "계정명"),
      fld("code", "계정코드"),
      fld("category", "계정분류", "select", [
        "자산",
        "부채",
        "자본",
        "수익",
        "비용",
      ]),
      fld("normal", "정상잔액", "select", ["차변", "대변"]),
    ],
    [
      col("code", "계정코드"),
      col("title", "계정명"),
      col("category", "분류"),
      col("normal", "정상잔액"),
    ],
    { filters: { key: "category", label: "분류" } },
  ),
  document("journals", "accounting", "전표", "JE", {
    hasLines: false,
    kind: "journal",
    initial: "작성중",
    statuses: ["작성중", "전기완료", "역분개완료"],
    fields: [
      fld("title", "적요"),
      fld("date", "전표일", "date"),
      fld("category", "전표유형", "select", [
        "일반전표",
        "매입전표",
        "매출전표",
        "입금전표",
        "출금전표",
        "급여전표",
        "역분개",
      ]),
    ],
    columns: [
      col("id", "전표번호"),
      col("date", "전표일"),
      col("title", "적요"),
      col("category", "유형"),
      col("amount", "차변 합계", "money"),
      col("owner", "작성자"),
      col("status", "상태", "status"),
    ],
    transitions: [
      transition("작성중", "전기완료", "전표 전기"),
      transition("전기완료", "역분개완료", "역분개 생성"),
    ],
    filters: { key: "category", label: "유형" },
    workflow: ["작성", "검증", "전기", "원장 반영"],
  }),
  master(
    "fiscal-periods",
    "accounting",
    "회계기간",
    "FP",
    [
      fld("title", "기간명"),
      fld("date", "시작일", "date"),
      fld("dueDate", "종료일", "date"),
    ],
    [
      col("title", "회계기간"),
      col("date", "시작일"),
      col("dueDate", "종료일"),
      col("journalCount", "전표수", "number"),
    ],
    {
      initial: "진행중",
      statuses: ["진행중", "마감"],
      transitions: [
        transition("진행중", "마감", "기간 마감"),
        transition("마감", "진행중", "기간 재개"),
      ],
    },
  ),
];
