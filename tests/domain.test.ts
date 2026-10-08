import { beforeEach, describe, it, expect } from "vitest";
import {
  resetDatabase,
  listRecords,
  getRecord,
  saveRecord,
  transitionRecord,
  approvalAction,
  database,
} from "../src/api/store";
import { blankRecord } from "../src/domain/helpers";
import { resourceByKey } from "../src/app/catalog";
function purchase() {
  const row = blankRecord(resourceByKey("purchase-requests")!, "김민희");
  row.title = "회귀 테스트 원자재 구매";
  row.values.reason = "테스트 생산 자재 요청";
  row.partner = "한빛전자";
  row.lines = [{ item: "PCB 기판 Type-A", qty: 2, price: 45000 }];
  return saveRecord("purchase-requests", row, "김민희");
}
beforeEach(() => resetDatabase());
describe("업무 상태와 문서 연결", () => {
  it("구매요청 → 결재 → 발주 → 입고 → 매입 → 채무 → 지급 → 전표", () => {
    let pr = purchase();
    pr = transitionRecord("purchase-requests", pr.id, "결재 상신", "김민희");
    const apId = pr.links.find((l) => l.resource === "approvals")!.id;
    expect(() => approvalAction(apId, "승인", "김민희")).toThrow();
    let ap = getRecord("approvals", apId);
    while (ap.status !== "승인완료") {
      ap = approvalAction(
        apId,
        "승인",
        ap.approvals.find((s) => s.status === "대기")!.name,
      );
    }
    expect(getRecord("purchase-requests", pr.id).status).toBe("승인완료");
    pr = transitionRecord("purchase-requests", pr.id, "발주 생성", "김민희");
    const poId = pr.links.find((l) => l.resource === "purchase-orders")!.id;
    const poDraft = getRecord("purchase-orders", poId);
    poDraft.values.warehouse = "본사 자재창고";
    saveRecord("purchase-orders", poDraft, "김민희");
    transitionRecord("purchase-orders", poId, "발주 확정", "김민희");
    const po = transitionRecord(
      "purchase-orders",
      poId,
      "입고 문서 생성",
      "김민희",
    );
    const grId = po.links.find((l) => l.resource === "goods-receipts")!.id;
    transitionRecord("goods-receipts", grId, "입고 확정", "김민희");
    const gr = transitionRecord(
      "goods-receipts",
      grId,
      "매입 문서 생성",
      "김민희",
    );
    const billId = gr.links.find((l) => l.resource === "purchase-bills")!.id;
    const billDraft = getRecord("purchase-bills", billId);
    billDraft.values.evidenceNumber = "TEST-BILL-01";
    saveRecord("purchase-bills", billDraft, "김민희");
    const bill = transitionRecord(
      "purchase-bills",
      billId,
      "매입 전기",
      "이회계팀장",
    );
    const payable = getRecord(
      "payables",
      bill.links.find((l) => l.resource === "payables")!.id,
    );
    expect(payable.values.balance).toBe(99000);
    const payment = blankRecord(resourceByKey("payments-out")!, "이회계팀장");
    payment.title = "테스트 지급";
    payment.partner = "한빛전자";
    payment.amount = 99000;
    payment.values.bank = "기업은행 123-456789-01";
    payment.values.allocations = JSON.stringify({ [payable.id]: 99000 });
    const saved = saveRecord("payments-out", payment, "이회계팀장");
    expect(getRecord("payables", payable.id).values.balance).toBe(0);
    expect(saved.links.some((l) => l.resource === "journals")).toBe(true);
    expect(
      database()["audit-events"].some((r) => r.values.reference === saved.id),
    ).toBe(true);
  });
  it("반려 사유 필수 및 재상신 시 결재선 초기화", () => {
    const pr = transitionRecord(
      "purchase-requests",
      purchase().id,
      "결재 상신",
      "김민희",
    );
    const id = pr.links[0].id;
    expect(() => approvalAction(id, "반려", "김팀장")).toThrow("사유");
    const denied = approvalAction(id, "반려", "김팀장", "예산 근거 누락");
    expect(denied.status).toBe("반려");
    expect(approvalAction(id, "상신", "김민희").approvals[1].status).toBe(
      "대기",
    );
  });
  it("결재 회수는 기안자만 가능", () => {
    const pr = transitionRecord(
      "purchase-requests",
      purchase().id,
      "결재 상신",
      "김민희",
    );
    const id = pr.links[0].id;
    expect(() => approvalAction(id, "회수", "김팀장")).toThrow();
    expect(approvalAction(id, "회수", "김민희").status).toBe("회수");
  });
  it("이미 확정된 문서는 수정할 수 없음", () => {
    const r = getRecord("purchase-orders", "PO-2026-0002");
    expect(() =>
      saveRecord("purchase-orders", { ...r, title: "불법 수정" }, "김민희"),
    ).toThrow("수정");
  });
});
describe("수량·금액 무결성", () => {
  it("지급 배분 초과 시 원본 상태 보존", () => {
    const p = database().payables.find((r) => Number(r.values.balance) > 0)!;
    const before = Number(p.values.balance);
    const row = blankRecord(resourceByKey("payments-out")!, "김민희");
    row.title = "초과지급";
    row.partner = p.partner;
    row.amount = before + 1;
    row.values.bank = "기업은행";
    row.values.allocations = JSON.stringify({ [p.id]: before + 1 });
    expect(() => saveRecord("payments-out", row, "김민희")).toThrow("잔액");
    expect(getRecord("payables", p.id).values.balance).toBe(before);
  });
  it("불균형 전표 거절, 역분개 시 원 전표 보존", () => {
    const row = blankRecord(resourceByKey("journals")!, "이회계팀장");
    row.title = "불균형";
    row.values.category = "일반전표";
    row.journal = [
      { account: "1010 보통예금", debit: 100, credit: 0 },
      { account: "2110 매입채무", debit: 0, credit: 90 },
    ];
    expect(() => saveRecord("journals", row, "이회계팀장")).toThrow("차변");
    const old = getRecord("journals", "JE-2026-0001");
    const reversed = transitionRecord(
      "journals",
      old.id,
      "역분개 생성",
      "이회계팀장",
    );
    expect(reversed.journal).toEqual(old.journal);
    const newId = reversed.links.find((l) => l.resource === "journals")!.id;
    const reversal = getRecord("journals", newId);
    expect(reversal.journal[0].credit).toBe(old.journal[0].debit);
    expect(() =>
      transitionRecord("journals", old.id, "역분개 생성", "이회계팀장"),
    ).toThrow();
  });
  it("마감 기간에 전기 거절", () => {
    const r = getRecord("journals", "JE-2026-0024");
    r.date = "2026-08-20";
    const saved = saveRecord("journals", r, "김민희");
    expect(() =>
      transitionRecord("journals", saved.id, "전표 전기", "이회계팀장"),
    ).toThrow("마감");
  });
  it("창고 이동 시 총 수량 보존, 재고 부족 시 롤백", () => {
    const before = database().stock.reduce(
      (n, r) => n + Number(r.values.qty),
      0,
    );
    const row = blankRecord(resourceByKey("stock-transfers")!, "김민희");
    row.title = "PCB 기판 Type-A";
    row.values.fromWarehouse = "본사 자재창고";
    row.values.toWarehouse = "용인 물류센터";
    row.values.qty = 3;
    row.values.reason = "정기 이동";
    const saved = saveRecord("stock-transfers", row, "김민희");
    transitionRecord("stock-transfers", saved.id, "이동 확정", "김민희");
    expect(database().stock.reduce((n, r) => n + Number(r.values.qty), 0)).toBe(
      before,
    );
    row.values.qty = 100000;
    const excessive = saveRecord("stock-transfers", row, "김민희");
    expect(() =>
      transitionRecord("stock-transfers", excessive.id, "이동 확정", "김민희"),
    ).toThrow("부족");
    expect(getRecord("stock-transfers", excessive.id).status).toBe("작성중");
  });
  it("검색·상태·날짜·페이지네이션이 함께 적용됨", () => {
    const result = listRecords("approvals", {
      status: "결재중",
      search: "구매",
      from: "2026-09-10",
      page: 1,
      size: 2,
    });
    expect(result.rows.length).toBeLessThanOrEqual(2);
    expect(
      result.rows.every((r) => r.status === "결재중" && r.date >= "2026-09-10"),
    ).toBe(true);
  });
  it("시드 전표 차대변 및 모든 관련 문서 링크 유효", () => {
    for (const [key, rows] of Object.entries(database()))
      for (const row of rows) {
        if (key === "journals")
          expect(row.journal.reduce((n, l) => n + l.debit - l.credit, 0)).toBe(
            0,
          );
        for (const link of row.links)
          expect(
            database()[link.resource]?.some((r) => r.id === link.id),
            `${key}/${row.id} → ${link.resource}/${link.id}`,
          ).toBe(true);
      }
  });
  it("시드 수불 누계가 품목·창고별 현재고와 일치 (INV-01)", () => {
    const db = database();
    for (const stock of db.stock) {
      const rows = db["stock-ledger"].filter(
        (r) =>
          r.title === stock.title &&
          r.values.warehouse === stock.values.warehouse,
      );
      let balance = 0;
      for (const r of rows) {
        balance += Number(r.values.inQty) - Number(r.values.outQty);
        expect(balance, `${r.id} 잔량`).toBeGreaterThanOrEqual(0);
        expect(Number(r.values.qty), `${r.id} 기록 잔량`).toBe(balance);
      }
      expect(balance, `${stock.title} @ ${stock.values.warehouse}`).toBe(
        Number(stock.values.qty),
      );
    }
  });
  it("BOM은 품목마다 사용중 버전이 하나이고 자기 자신을 투입하지 않음", () => {
    const boms = database().boms;
    for (const title of new Set(boms.map((b) => b.title))) {
      const active = boms.filter(
        (b) => b.title === title && b.status === "사용중",
      );
      expect(active, title).toHaveLength(1);
    }
    for (const b of boms)
      expect(b.lines.map((l) => l.item), b.id).not.toContain(b.title);
  });
  it("시드 전표: 계정 잔액이 중간에도 음수가 아니고 자산 = 부채 + 자본", () => {
    const posted = database()
      .journals.filter((r) => r.status === "전기완료")
      .sort((a, b) => a.date.localeCompare(b.date) || b.id.localeCompare(a.id));
    const balance: Record<string, number> = {};
    for (const doc of posted)
      for (const l of doc.journal) {
        const sign = /^[156]/.test(l.account) ? 1 : -1;
        balance[l.account] = (balance[l.account] ?? 0) + sign * (l.debit - l.credit);
        expect(balance[l.account], `${doc.id} ${l.account}`).toBeGreaterThanOrEqual(0);
      }
    const total = (prefix: string) =>
      Object.entries(balance)
        .filter(([a]) => prefix.includes(a[0]))
        .reduce((n, [, v]) => n + v, 0);
    const income = total("4") - total("56");
    expect(total("1")).toBe(total("2") + total("3") + income);
    expect(income).toBeGreaterThan(0);
  });
});
