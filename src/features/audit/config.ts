import { document, moduleConfig, col, fld } from "../../domain/config";
export const auditModule = moduleConfig("audit", "감사로그", "AUDIT LOG", [
  ["events", "감사로그", "audit-events"],
]);
export const auditResources = [
  document("audit-events", "audit", "감사로그", "LOG", {
    kind: "audit",
    readOnly: true,
    hasLines: false,
    statuses: ["완료"],
    initial: "완료",
    description:
      "누가, 언제, 어떤 업무를 처리했는지 확인합니다. 기록은 조회 전용입니다.",
    columns: [
      col("time", "처리 일시"),
      col("owner", "수행자"),
      col("event", "이벤트"),
      col("reference", "대상 문서"),
      col("title", "처리 내용"),
      col("status", "결과", "status"),
    ],
    fields: [
      fld("time", "처리 일시"),
      fld("event", "이벤트"),
      fld("reference", "대상 문서"),
      fld("before", "변경 전"),
      fld("after", "변경 후"),
    ],
    filters: { key: "event", label: "이벤트" },
  }),
];
