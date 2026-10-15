import { beforeEach, describe, it, expect } from "vitest";
import {
  database,
  resetDatabase,
  saveRecord,
  getRecord,
  transitionRecord,
} from "../src/api/store";
import { blankRecord } from "../src/domain/helpers";
import { resourceByKey } from "../src/app/catalog";
import {
  fromPurchaseSource,
  initializePurchase,
  receiptProgress,
} from "../src/features/purchasing/model";
const actor = "김민희";
const draft = (key: string) =>
  initializePurchase(key, blankRecord(resourceByKey(key)!, actor));
function order(two = false) {
  const p = draft("purchase-orders");
  p.partner = "한빛전자";
  p.values.warehouse = "본사 자재창고";
  p.lines = [
    { item: "PCB 기판 Type-A", qty: 100, price: 1000 },
    ...(two ? [{ item: "알루미늄 하우징", qty: 100, price: 2000 }] : []),
  ];
  const saved = saveRecord("purchase-orders", p, actor);
  return transitionRecord("purchase-orders", saved.id, "발주 확정", actor);
}
function receipt(po: ReturnType<typeof order>, qty: number) {
  const r = fromPurchaseSource(
    "goods-receipts",
    draft("goods-receipts"),
    getRecord("purchase-orders", po.id),
    "purchase-orders",
    database()["goods-receipts"],
  );
  r.lines = r.lines.slice(0, 1).map((l) => ({ ...l, qty }));
  return saveRecord("goods-receipts", r, actor);
}
function bill(gr: ReturnType<typeof receipt>) {
  const b = fromPurchaseSource(
    "purchase-bills",
    draft("purchase-bills"),
    gr,
    "goods-receipts",
  );
  b.values.evidenceNumber = "SUPPLIER-001";
  return saveRecord("purchase-bills", b, actor);
}
beforeEach(() => resetDatabase());
describe("구매 문서별 Mock 흐름", () => {
  it("PR은 거래처 없이 저장되고 품목 ID와 메타정보를 보존한다", () => {
    const r = draft("purchase-requests");
    r.title = "자재 요청";
    r.values.reason = "생산용";
    r.lines = [{ item: "PCB 기판 Type-A", qty: 2, price: 1000 }];
    const saved = saveRecord("purchase-requests", r, actor);
    expect(saved.status).toBe("작성중");
    expect(saved.partner).toBe("");
    expect(saved.lines[0].itemId).toBeTruthy();
    expect(saved.lines[0].unit).toBe("EA");
  });
  it("입고 초안은 PO 상태와 재고를 바꾸지 않고 확정 30 + 70만 누계한다", () => {
    const po = order();
    const stock = structuredClone(database().stock);
    const first = receipt(po, 30);
    expect(getRecord("purchase-orders", po.id).status).toBe("확정");
    expect(database().stock).toEqual(stock);
    transitionRecord("goods-receipts", first.id, "입고 확정", actor);
    expect(getRecord("purchase-orders", po.id).status).toBe("부분입고");
    const second = receipt(po, 70);
    transitionRecord("goods-receipts", second.id, "입고 확정", actor);
    expect(getRecord("purchase-orders", po.id).status).toBe("입고완료");
    expect(receiptProgress(po, database()["goods-receipts"])[0].received).toBe(
      100,
    );
    expect(() =>
      transitionRecord("purchase-orders", po.id, "입고 문서 생성", actor),
    ).toThrow();
  });
  it("오래된 초안 확정 초과는 수불과 PO에 부분 반영되지 않는다", () => {
    const po = order();
    const a = receipt(po, 70),
      b = receipt(po, 40);
    transitionRecord("goods-receipts", a.id, "입고 확정", actor);
    const before = structuredClone(database());
    expect(() =>
      transitionRecord("goods-receipts", b.id, "입고 확정", actor),
    ).toThrow("잔여수량");
    expect(database()).toEqual(before);
  });
  it("여러 품목의 원천 행별 잔량을 구별한다", () => {
    const po = order(true);
    const gr = receipt(po, 100);
    transitionRecord("goods-receipts", gr.id, "입고 확정", actor);
    expect(getRecord("purchase-orders", po.id).status).toBe("부분입고");
    expect(
      receiptProgress(po, database()["goods-receipts"]).map((l) => l.remaining),
    ).toEqual([0, 100]);
  });
  it("완료 전 입고 문서가 있어도 잔량으로 추가 초안을 생성한다", () => {
    const po = order();
    let p = transitionRecord("purchase-orders", po.id, "입고 문서 생성", actor);
    p = transitionRecord("purchase-orders", po.id, "입고 문서 생성", actor);
    expect(p.links.filter((l) => l.resource === "goods-receipts")).toHaveLength(
      2,
    );
    expect(p.status).toBe("확정");
  });
  it("0·음수 입고와 미확정 PO 참조를 거부한다", () => {
    const po = order();
    expect(() => receipt(po, 0)).toThrow();
    expect(() => receipt(po, -1)).toThrow();
    const r = receipt(po, 1);
    database()["purchase-orders"].find((x) => x.id === po.id)!.status =
      "작성중";
    expect(() =>
      transitionRecord("goods-receipts", r.id, "입고 확정", actor),
    ).toThrow("확정");
  });
  it("입고 확정 재시도는 수불을 중복 생성하지 않는다", () => {
    const po = order(),
      r = receipt(po, 30);
    transitionRecord("goods-receipts", r.id, "입고 확정", actor);
    const before = structuredClone(database());
    expect(() =>
      transitionRecord("goods-receipts", r.id, "입고 확정", actor),
    ).toThrow();
    expect(database()).toEqual(before);
  });
  it("매입 증빙/전기일/지급기한을 보존하고 세금 포함 AP 및 전표를 만든다", () => {
    const po = order(),
      r = receipt(po, 100);
    const gr = transitionRecord("goods-receipts", r.id, "입고 확정", actor);
    const b = bill(gr);
    b.values.evidenceDate = "2026-09-28";
    b.date = "2026-09-29";
    b.dueDate = "2026-10-28";
    saveRecord("purchase-bills", b, actor);
    const posted = transitionRecord("purchase-bills", b.id, "매입 전기", actor);
    const ap = getRecord(
      "payables",
      posted.links.find((l) => l.resource === "payables")!.id,
    );
    expect(ap.amount).toBe(110000);
    expect(ap.values.balance).toBe(110000);
    expect(ap.values.billId).toBe(b.id);
    expect(ap.dueDate).toBe("2026-10-28");
    const journal = getRecord(
      "journals",
      posted.links.find((l) => l.resource === "journals")!.id,
    );
    expect(journal.journal.reduce((n, l) => n + l.credit, 0)).toBe(110000);
    expect(
      journal.journal.find((l) => l.account.includes("부가세"))?.debit,
    ).toBe(10000);
  });
  it("PO 직접 참조 매입과 면세 금액도 연결한다", () => {
    const po = order();
    const b = fromPurchaseSource(
      "purchase-bills",
      draft("purchase-bills"),
      po,
      "purchase-orders",
    );
    b.values.evidenceNumber = "DIRECT-PO";
    b.values.taxType = "면세 0%";
    const saved = saveRecord("purchase-bills", b, actor);
    expect(saved.links.some((l) => l.id === po.id)).toBe(true);
    const posted = transitionRecord(
      "purchase-bills",
      saved.id,
      "매입 전기",
      actor,
    );
    expect(
      getRecord(
        "payables",
        posted.links.find((l) => l.resource === "payables")!.id,
      ).amount,
    ).toBe(100000);
  });
  it("지급액·배분합계 불일치를 거부하고 정상 부분지급은 잔액만 줄인다", () => {
    const po = order(),
      r = receipt(po, 100),
      gr = transitionRecord("goods-receipts", r.id, "입고 확정", actor),
      b = bill(gr);
    const posted = transitionRecord("purchase-bills", b.id, "매입 전기", actor),
      id = posted.links.find((l) => l.resource === "payables")!.id;
    const pay = draft("payments-out");
    pay.title = "외부 지급 기록";
    pay.partner = "한빛전자";
    pay.values.bank = "기업은행 123-456789-01";
    pay.amount = 60000;
    pay.values.allocations = JSON.stringify({ [id]: 50000 });
    expect(() => saveRecord("payments-out", pay, actor)).toThrow("합계");
    expect(getRecord("payables", id).values.balance).toBe(110000);
    pay.values.allocations = JSON.stringify({ [id]: 60000 });
    saveRecord("payments-out", pay, actor);
    expect(getRecord("payables", id).values.balance).toBe(50000);
    expect(getRecord("payables", id).values.paidAmount).toBe(60000);
  });
  it("입고 원천을 바꾸면 양쪽의 이전 연결을 제거한다", () => {
    const a = order(),
      b = order(),
      r = receipt(a, 10);
    const updated = fromPurchaseSource(
      "goods-receipts",
      r,
      b,
      "purchase-orders",
      database()["goods-receipts"],
    );
    saveRecord("goods-receipts", updated, actor);
    expect(
      getRecord("purchase-orders", a.id).links.some((l) => l.id === r.id),
    ).toBe(false);
    expect(
      getRecord("purchase-orders", b.id).links.some((l) => l.id === r.id),
    ).toBe(true);
  });
  it("마감 전기 실패는 매입 초안·AP·분개에 부분 결과를 남기지 않는다", () => {
    const po = order(),
      r = receipt(po, 100),
      gr = transitionRecord("goods-receipts", r.id, "입고 확정", actor),
      b = bill(gr);
    b.date = "2026-08-20";
    saveRecord("purchase-bills", b, actor);
    const before = structuredClone(database());
    expect(() =>
      transitionRecord("purchase-bills", b.id, "매입 전기", actor),
    ).toThrow("마감");
    expect(database()).toEqual(before);
  });
});
