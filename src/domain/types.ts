export type Value = string | number;
export interface Line {
  id?: string;
  itemId?: string;
  itemCode?: string;
  itemType?: string;
  specification?: string;
  unit?: string;
  sourceLineId?: string;
  item: string;
  qty: number;
  price: number;
}
export interface JournalLine {
  account: string;
  debit: number;
  credit: number;
}
export interface ApprovalStep {
  name: string;
  role: string;
  status: "기안" | "승인" | "대기" | "예정" | "반려";
}
export interface DocLink {
  resource: string;
  id: string;
}
export interface HistoryEntry {
  actor: string;
  at: string;
  action: string;
}
export interface RecordData {
  id: string;
  title: string;
  status: string;
  date: string;
  owner: string;
  partner: string;
  department: string;
  amount: number;
  dueDate: string;
  values: Record<string, Value>;
  lines: Line[];
  journal: JournalLine[];
  approvals: ApprovalStep[];
  links: DocLink[];
  history: HistoryEntry[];
}
export interface Field {
  key: string;
  label: string;
  type?: "text" | "number" | "date" | "select" | "money";
  options?: string[];
  required?: boolean;
  min?: number;
}
export interface Column {
  key: string;
  label: string;
  type?: "money" | "status" | "progress" | "number";
  width?: number;
}
export interface Transition {
  from: string[];
  to: string;
  label: string;
  target?: string;
  danger?: boolean;
}
export interface Resource {
  key: string;
  module: string;
  label: string;
  singular: string;
  prefix: string;
  description: string;
  columns: Column[];
  fields: Field[];
  statuses: string[];
  initial: string;
  kind?:
    | "document"
    | "master"
    | "stock"
    | "journal"
    | "approval"
    | "payment"
    | "audit";
  readOnly?: boolean;
  hasLines?: boolean;
  transitions?: Transition[];
  workflow?: string[];
  source?: string;
  filters?: { label: string; key: string };
}
export interface Module {
  key: string;
  label: string;
  caption: string;
  icon: string;
  items: {
    key: string;
    label: string;
    resource?: string;
    status?: string;
    ownerOnly?: boolean;
  }[];
}
export interface ListParams {
  search?: string;
  status?: string;
  entity?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
  direction?: "asc" | "desc";
  owner?: string;
  participant?: string;
}
export interface ListResult {
  rows: RecordData[];
  total: number;
  all: RecordData[];
}
export interface MutationInput {
  resource: string;
  record: RecordData;
  actor: string;
}
export const DEMO_DATE = "2026-09-29";
export const PEOPLE = ["김민희", "이회계팀장", "김팀장", "박본부장", "최이사"];
export const PARTNERS = [
  "한빛전자",
  "성진정밀",
  "대한소재",
  "미래테크",
  "ABC전자",
  "우성산업",
  "네오모빌리티",
  "태성패키징",
];
export const DEPARTMENTS = [
  "경영지원팀",
  "구매팀",
  "영업팀",
  "생산1팀",
  "생산2팀",
  "개발팀",
  "물류팀",
  "인사팀",
];
export const ITEMS = [
  "정밀 제어 모듈 K-100",
  "산업용 센서 S-200",
  "알루미늄 하우징",
  "PCB 기판 Type-A",
  "서보 모터 M-50",
  "맥북 Pro 14",
  "포장재 세트",
];
export const WAREHOUSES = ["본사 자재창고", "화성 생산창고", "용인 물류센터"];
