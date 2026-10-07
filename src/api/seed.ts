import type { RecordData, Resource } from "../domain/types";
import { PARTNERS, DEPARTMENTS, ITEMS, WAREHOUSES } from "../domain/types";
import { resources } from "../app/catalog";
import { inventoryFixture } from "../features/inventory/mock";
import { hrFixture } from "../features/hr/mock";
import { manufacturingFixture } from "../features/manufacturing/mock";
import { accountingFixture } from "../features/accounting/mock";
import { paymentsFixture } from "../features/payments/mock";
import { blankRecord } from "../domain/helpers";
export type Database = Record<string, RecordData[]>;
export function seedResource(r: Resource): RecordData[] {
  return Array.from(
    {
      length:
        (
          {
            warehouses: 3,
            items: 7,
            departments: 8,
            positions: 6,
            accounts: 12,
            "fiscal-periods": 12,
            boms: 6,
            stock: 21,
            "approval-lines": 4,
            "approval-policies": 4,
          } as Record<string, number>
        )[r.key] ?? 24,
    },
    (_, i) => {
      const row = blankRecord(r, "김민희");
      const n = i + 1;
      row.id = `${r.prefix}-2026-${String(n).padStart(4, "0")}`;
      row.partner = PARTNERS[i % PARTNERS.length];
      row.owner = ["김민희", "정수현", "박지훈", "김민희", "이서연", "김도윤"][
        i % 6
      ];
      row.department = DEPARTMENTS[i % DEPARTMENTS.length];
      row.date = `2026-09-${String(29 - (i % 24)).padStart(2, "0")}`;
      row.dueDate = `2026-10-${String(1 + (i % 12)).padStart(2, "0")}`;
      row.title = `${ITEMS[i % ITEMS.length]} ${r.singular}`;
      row.status = r.statuses[i % r.statuses.length];
      row.lines = r.hasLines
        ? [
            {
              item: ITEMS[i % ITEMS.length],
              qty: 10 + (i % 6) * 10,
              price: 120000 + (i % 7) * 35000,
            },
          ]
        : [];
      row.amount =
        row.lines.reduce((a, l) => a + l.qty * l.price, 0) ||
        1250000 + n * 185000;
      r.fields.forEach((f) => {
        if (f.key in row) return;
        row.values[f.key] =
          f.type === "select"
            ? ((f.options ?? [])[i % (f.options?.length ?? 1)] ?? "")
            : f.type === "date"
              ? row.date
              : f.type === "number"
                ? 10 + n
                : f.type === "money"
                  ? row.amount
                  : `${f.label} ${n}`;
      });
      row.values.reason = "9월 생산계획 및 운영 일정에 따른 업무 요청입니다.";
      row.values.memo = "담당 부서 검토 후 업무를 진행해 주세요.";
      row.values.warehouse = WAREHOUSES[i % 3];
      if (r.key === "approvals") {
        row.values.docType = [
          "구매요청서",
          "지출결의서",
          "구매품의서",
          "연차신청서",
        ][i % 4];
        row.title = [
          "개발팀 업무용 노트북 구매 요청",
          "9월 원자재 추가 구매 품의",
          "화성공장 설비 유지보수 비용",
          "연차 사용 신청",
          "생산라인 센서 교체 구매",
          "물류센터 포장재 구매 요청",
        ][i % 6];
        row.status = [
          "결재중",
          "결재중",
          "승인완료",
          "작성중",
          "반려",
          "상신",
          "승인완료",
          "결재중",
          "회수",
        ][i % 9];
        if (row.values.docType === "연차신청서") {
          row.lines = [];
          row.amount = 0;
        }
        row.approvals = [
          { name: row.owner, role: "기안", status: "기안" },
          {
            name: "김팀장",
            role: "팀장",
            status:
              row.status === "작성중" ||
              row.status === "상신" ||
              row.status === "회수"
                ? "예정"
                : "승인",
          },
          {
            name: "박본부장",
            role: "본부장",
            status:
              row.status === "작성중" ||
              row.status === "상신" ||
              row.status === "회수"
                ? "예정"
                : "승인",
          },
          {
            name: "이회계팀장",
            role: "재무 검토",
            status:
              row.status === "승인완료"
                ? "승인"
                : row.status === "반려"
                  ? "반려"
                  : row.status === "결재중"
                    ? "대기"
                    : "예정",
          },
          {
            name: "최이사",
            role: "최종 승인",
            status: row.status === "승인완료" ? "승인" : "예정",
          },
        ];
        if (row.status === "상신") row.approvals[1].status = "대기";
        if (row.status === "반려")
          row.values.rejectReason = "예산 근거 자료를 보완해 주세요.";
      }
      if (r.key === "approvals" && row.values.docType === "연차신청서") {
        row.title = `${row.owner} 연차 사용 신청`;
        row.values.startDate = row.date;
        row.values.endDate = row.date;
      }
      if (r.key === "approvals" && row.title.includes("노트북")) {
        row.lines = [{ item: "맥북 Pro 14", qty: 10, price: 2490000 }];
        row.amount = 24900000;
      }
      if (r.key === "approval-lines") {
        row.title = [
          "일반 구매 결재",
          "고액 구매 결재",
          "연차 신청 결재",
          "경비 지출 결재",
        ][i];
        row.values.first = "김팀장";
        row.values.second = "이회계팀장";
        row.values.third = "최이사";
      }
      if (r.key === "approval-policies") {
        row.title = [
          "일반 구매 승인",
          "1천만원 이상 구매",
          "휴가 승인",
          "지출결의 승인",
        ][i];
        row.values.threshold = i === 1 ? 10000000 : 0;
        row.values.lineName = [
          "일반 구매 결재",
          "고액 구매 결재",
          "연차 신청 결재",
          "경비 지출 결재",
        ][i];
      }
      if (r.module === "inventory") inventoryFixture(r.key, row, i);
      if (r.module === "hr") hrFixture(r.key, row, i);
      if (r.module === "manufacturing") manufacturingFixture(r.key, row, i);
      if (r.module === "accounting") accountingFixture(r.key, row, i);
      paymentsFixture(r.key, row, i);
      if (r.key === "audit-events") {
        row.values.time = `${row.date} 10:${String(i + 10).padStart(2, "0")}:00`;
        row.values.event = [
          "문서 등록",
          "문서 수정",
          "승인",
          "입고 확정",
          "전표 전기",
        ][i % 5];
        row.values.reference = `PO-2026-${String(i + 1).padStart(4, "0")}`;
        row.title = `${row.values.reference} ${row.values.event}`;
        row.values.before = "작성중";
        row.values.after = "확정";
        row.links = [
          { resource: "purchase-orders", id: String(row.values.reference) },
        ];
      }
      row.history = [
        { actor: row.owner, at: `${row.date} 09:30`, action: "문서 등록" },
        {
          actor: "김팀장",
          at: `${row.date} 10:15`,
          action: `현재 상태: ${row.status}`,
        },
      ];
      return row;
    },
  );
}
export function createSeed(): Database {
  const db = Object.fromEntries(resources.map((r) => [r.key, seedResource(r)]));
  const connect = (keys: string[], count = 8) => {
    for (let i = 0; i < count; i++) {
      const chain = keys
        .map((k) => ({ resource: k, row: db[k]?.[i] }))
        .filter((x) => x.row);
      for (const x of chain)
        x.row.links = [
          ...x.row.links,
          ...chain
            .filter((y) => y !== x)
            .map((y) => ({ resource: y.resource, id: y.row.id })),
        ];
    }
  };
  connect([
    "purchase-orders",
    "goods-receipts",
    "purchase-bills",
    "payables",
    "payments-out",
  ]);
  connect([
    "sales-orders",
    "deliveries",
    "invoices",
    "receivables",
    "payments-in",
  ]);
  for (let i = 0; i < 8; i++) {
    for (const [keys, statuses] of [
      [
        [
          "purchase-orders",
          "goods-receipts",
          "purchase-bills",
          "payables",
          "payments-out",
        ],
        ["입고완료", "입고완료", "전기완료", "지급완료", "지급완료"],
      ],
      [
        [
          "sales-orders",
          "deliveries",
          "invoices",
          "receivables",
          "payments-in",
        ],
        ["출고완료", "출고완료", "전기완료", "수금완료", "수금완료"],
      ],
    ]) {
      const amount = db[keys[0]]?.[i]?.amount ?? 0;
      keys.forEach((key, k) => {
        const row = db[key]?.[i];
        if (row) {
          row.status = statuses[k];
          row.amount = amount;
          if (key === "payables" || key === "receivables")
            row.values.balance = 0;
          row.history[row.history.length - 1].action =
            `현재 상태: ${row.status}`;
        }
      });
    }
  }
  connect(["boms", "work-orders", "material-issues", "production-receipts"], 6);
  for (let i = 0; i < Math.min(24, db.approvals?.length ?? 0); i++) {
    const ap = db.approvals[i];
    const pr = db["purchase-requests"]?.[i];
    if (pr && ap.values.docType !== "연차신청서") {
      pr.title = ap.title;
      pr.amount = ap.amount;
      pr.lines = structuredClone(ap.lines);
      pr.status =
        ap.status === "승인완료"
          ? "승인완료"
          : ["작성중", "회수", "반려"].includes(ap.status)
            ? "작성중"
            : "승인대기";
      pr.links.push({ resource: "approvals", id: ap.id });
      ap.links.push({ resource: "purchase-requests", id: pr.id });
    }
  }
  return db;
}
