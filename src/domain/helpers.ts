import type { Field, RecordData, Resource } from "./types";
export const fieldValue = (row: RecordData, key: string): string | number =>
  key in row && typeof row[key as keyof RecordData] !== "object"
    ? (row[key as keyof RecordData] as string | number)
    : (row.values[key] ?? "");
export const won = (value: number | string) =>
  `${new Intl.NumberFormat("ko-KR").format(Number(value))}원`;
export const shortWon = (n: number) =>
  Math.abs(n) >= 100000000
    ? `${(n / 100000000).toFixed(2)}억`
    : Math.abs(n) >= 10000
      ? `${(n / 10000).toLocaleString("ko-KR", { maximumFractionDigits: 0 })}만`
      : n.toLocaleString("ko-KR");
export const sum = (rows: RecordData[], field = "amount") =>
  rows.reduce((total, row) => total + Number(fieldValue(row, field) || 0), 0);
export const amountOf = (lines: RecordData["lines"]) =>
  lines.reduce((n, l) => n + l.qty * l.price, 0);
export const taxOf = (amount: number) => Math.round(amount / 10);
export function blankRecord(resource: Resource, actor: string): RecordData {
  const values: Record<string, string | number> = {};
  resource.fields.forEach((f) => {
    values[f.key] =
      f.type === "number" || f.type === "money"
        ? (f.min ?? 0)
        : f.type === "date"
          ? "2026-09-29"
          : "";
  });
  return {
    id: "",
    title: "",
    status: resource.initial,
    date: "2026-09-29",
    dueDate: "2026-10-06",
    owner: actor,
    partner: "",
    department: "경영지원팀",
    amount: 0,
    values,
    lines: resource.hasLines ? [{ item: "", qty: 1, price: 0 }] : [],
    journal:
      resource.kind === "journal"
        ? [
            { account: "1310 원재료", debit: 0, credit: 0 },
            { account: "2110 매입채무", debit: 0, credit: 0 },
          ]
        : [],
    approvals: [],
    links: [],
    history: [],
  };
}
export const baseFields: Field[] = [
  { key: "title", label: "문서 제목", required: true },
  { key: "partner", label: "거래처", type: "select", required: true },
  { key: "date", label: "작성일", type: "date", required: true },
  { key: "dueDate", label: "납기일", type: "date", required: true },
];
export const documentColumns = [
  { key: "id", label: "문서번호" },
  { key: "title", label: "문서명" },
  { key: "partner", label: "거래처" },
  { key: "date", label: "작성일" },
  { key: "dueDate", label: "납기일" },
  { key: "amount", label: "공급가액", type: "money" as const },
  { key: "status", label: "상태", type: "status" as const },
];
