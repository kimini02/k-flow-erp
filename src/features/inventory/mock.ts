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
      "상품",
      "원재료",
      "원재료",
      "상품",
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
// Ledger entries come from every confirmed source document — receipts, deliveries,
// material issues, production receipts, transfers and adjustments (ERD:
// StockLedgerEntry is the record). Each item x warehouse starts from a mock opening balance on 2026-08-31,
// and the seeded stock row is then set to opening + confirmed movements.
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
  add(db["material-issues"], "완료", "생산투입", -1, "material-issues");
  add(
    db["production-receipts"],
    "입고완료",
    "생산완료",
    1,
    "production-receipts",
  );
  // Stock transfers are immediate in the mock (DR-21 open): one issue at the source,
  // one receipt at the destination, as the runtime confirm does.
  for (const t of db["stock-transfers"] ?? []) {
    if (t.status !== "완료") continue;
    const qty = Number(t.values.qty ?? 0);
    const source = { resource: "stock-transfers", id: t.id };
    events.push(
      {
        date: t.date,
        item: t.title,
        warehouse: String(t.values.fromWarehouse),
        delta: -qty,
        movement: "창고이동",
        source,
      },
      {
        date: t.date,
        item: t.title,
        warehouse: String(t.values.toWarehouse),
        delta: qty,
        movement: "창고이동",
        source,
      },
    );
  }
  for (const a of db["stock-adjustments"] ?? []) {
    if (a.status !== "완료") continue;
    const sign = a.values.direction === "감소" ? -1 : 1;
    events.push({
      date: a.date,
      item: a.title,
      warehouse: String(a.values.warehouse),
      delta: sign * Number(a.values.qty ?? 0),
      movement: "재고조정",
      source: { resource: "stock-adjustments", id: a.id },
    });
  }
  const rows: (Movement & { price: number; balance: number })[] = [];
  for (const stock of db.stock ?? []) {
    const item = stock.title;
    const warehouse = String(stock.values.warehouse);
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
    // The seeded quantity is the opening balance, raised only as far as needed so a
    // confirmed issue never draws stock below zero.
    const opening = Math.max(Number(stock.values.qty), -lowest);
    const sequence: Movement[] = [
      {
        date: "2026-08-31",
        item,
        warehouse,
        delta: opening,
        movement: "기초재고",
      },
      ...own,
    ];
    let balance = 0;
    for (const e of sequence) {
      balance += e.delta;
      rows.push({ ...e, price, balance });
    }
    // Current stock is the ledger result, not an independent number.
    const reserved = Math.min(Number(stock.values.reserved ?? 0), balance);
    stock.values.qty = balance;
    stock.values.reserved = reserved;
    stock.values.available = balance - reserved;
    stock.amount = balance * price;
    stock.status =
      balance - reserved < Number(stock.values.safety ?? 0) ? "부족" : "정상";
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
          ? "Mock 기초재고(2026-08-31). 현재고 = 기초 + 9월 확정 수불 (기초재고 정책 DR-10/20 미정)"
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
