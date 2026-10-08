import type { RecordData } from "../../domain/types";
import { ITEMS, WAREHOUSES } from "../../domain/types";
export function inventoryFixture(key: string, r: RecordData, i: number) {
  r.partner = "";
  if (["stock", "items", "stock-transfers", "stock-adjustments"].includes(key))
    r.title = ITEMS[i % ITEMS.length];
  if (key === "stock") {
    r.values.qty = i % 5 === 0 ? 25 : 120 + i * 20;
    r.values.reserved = (i % 3) * 10;
    r.values.available = Number(r.values.qty) - Number(r.values.reserved);
    r.values.safety = 80;
    r.values.price = 120000 + (i % 7) * 35000;
    r.values.warehouse = WAREHOUSES[Math.floor(i / 7) % 3];
    r.amount = Number(r.values.qty) * Number(r.values.price);
    r.status = Number(r.values.available) < 80 ? "부족" : "정상";
  }
  if (key === "items") {
    r.values.category = [
      "완제품",
      "완제품",
      "원재료",
      "원재료",
      "반제품",
      "소모품",
      "소모품",
    ][i % 7];
    r.values.unit = "EA";
    r.values.price = 120000 + (i % 7) * 35000;
    r.values.safety = 80;
    r.status = "사용중";
    r.amount = 0;
  }
  if (key === "warehouses") {
    r.title = WAREHOUSES[i % 3];
    r.values.address = [
      "경기도 성남시 분당구",
      "경기도 화성시 동탄산단",
      "경기도 용인시 처인구",
    ][i % 3];
    r.values.manager = ["최정우", "김태윤", "박수진"][i % 3];
    r.values.capacity = [10000, 24000, 18000][i % 3];
    r.values.utilization = [64, 78, 52][i % 3];
    r.amount = 0;
    r.status = "사용중";
  }
  if (key === "stock-transfers") {
    r.values.fromWarehouse = WAREHOUSES[i % 3];
    r.values.toWarehouse = WAREHOUSES[(i + 1) % 3];
    r.values.qty = 10 + i;
    r.status = i % 2 ? "완료" : "작성중";
  }
  if (key === "stock-adjustments") {
    r.values.direction = i % 2 ? "증가" : "감소";
    r.values.qty = 2 + (i % 5);
    r.values.reason = ["정기 실사 차이", "파손 및 폐기", "미등록 재고 발견"][
      i % 3
    ];
  }
}

type Movement = {
  date: string;
  item: string;
  warehouse: string;
  delta: number;
  movement: string;
  source?: { resource: string; id: string };
};
// Ledger entries come from confirmed source documents (ERD: StockLedgerEntry is the
// record, current stock is its total). The opening balance is back-calculated so that
// opening + receipts - issues = the seeded current stock for every item x warehouse.
export function stockLedgerFromDocuments(
  db: Record<string, RecordData[]>,
  blank: () => RecordData,
): RecordData[] {
  const events: Movement[] = [];
  const add = (
    rows: RecordData[] | undefined,
    done: string,
    movement: string,
    sign: 1 | -1,
    resource: string,
  ) => {
    for (const r of rows ?? []) {
      if (r.status !== done) continue;
      const lines = r.lines.length
        ? r.lines
        : [{ item: r.title, qty: Number(r.values.qty ?? 0), price: 0 }];
      for (const l of lines)
        if (l.qty > 0)
          events.push({
            date: r.date,
            item: l.item,
            warehouse: String(r.values.warehouse),
            delta: sign * l.qty,
            movement,
            source: { resource, id: r.id },
          });
    }
  };
  add(db["goods-receipts"], "입고완료", "구매입고", 1, "goods-receipts");
  add(db.deliveries, "출고완료", "판매출고", -1, "deliveries");
  // Only the production documents linked to BOM/work orders in the seed.
  add(
    db["material-issues"]?.slice(0, 6),
    "완료",
    "생산투입",
    -1,
    "material-issues",
  );
  add(
    db["production-receipts"]?.slice(0, 6),
    "입고완료",
    "생산완료",
    1,
    "production-receipts",
  );
  const rows: (Movement & { price: number; balance: number })[] = [];
  for (const stock of db.stock ?? []) {
    const item = stock.title;
    const warehouse = String(stock.values.warehouse);
    const target = Number(stock.values.qty);
    const price = Number(stock.values.price ?? 0);
    const own = events
      .filter((e) => e.item === item && e.warehouse === warehouse)
      .sort((a, b) => a.date.localeCompare(b.date) || b.delta - a.delta);
    let running = 0,
      lowest = 0;
    for (const e of own) {
      running += e.delta;
      lowest = Math.min(lowest, running);
    }
    const opening = Math.max(target - running, -lowest);
    const adjustment = target - opening - running;
    const sequence: Movement[] = [
      {
        date: "2026-08-31",
        item,
        warehouse,
        delta: opening,
        movement: "기초재고",
      },
      ...own,
      ...(adjustment
        ? [
            {
              date: "2026-09-29",
              item,
              warehouse,
              delta: adjustment,
              movement: "재고조정",
            },
          ]
        : []),
    ];
    let balance = 0;
    for (const e of sequence) {
      balance += e.delta;
      rows.push({ ...e, price, balance });
    }
  }
  rows.sort(
    (a, b) =>
      a.date.localeCompare(b.date) ||
      a.item.localeCompare(b.item, "ko") ||
      a.warehouse.localeCompare(b.warehouse, "ko"),
  );
  const owners = ["박지훈", "정수현", "김민희"];
  return rows.map((e, i) => {
    const r = blank();
    r.id = `SL-2026-${String(i + 1).padStart(4, "0")}`;
    r.title = e.item;
    r.partner = "";
    r.date = e.date;
    r.dueDate = e.date;
    r.owner =
      owners[WAREHOUSES.indexOf(e.warehouse) % owners.length] ?? "김민희";
    r.status = "완료";
    r.amount = Math.abs(e.delta) * e.price;
    r.values = {
      warehouse: e.warehouse,
      movement: e.movement,
      inQty: Math.max(0, e.delta),
      outQty: Math.max(0, -e.delta),
      qty: e.balance,
      reference: e.source?.id ?? "",
      memo:
        e.movement === "기초재고"
          ? "Mock 기초재고: 현재고와 9월 확정 수불로 역산 (기초재고 정책 DR-10/20 미정)"
          : e.movement === "재고조정"
            ? "정기 실사 차이 반영"
            : "확정된 원천 문서 기준 수불",
    };
    r.lines = [];
    r.links = e.source ? [e.source] : [];
    r.history = [
      {
        actor: r.owner,
        at: `${e.date} 18:00`,
        action: `${e.movement} 수불 확정`,
      },
    ];
    return r;
  });
}
