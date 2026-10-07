import {
  isPurchase,
  sourceKey,
  validatePurchase,
  receiptProgress,
  purchaseTotals,
} from "../features/purchasing/model";
import type { Database } from "../api/seed";
import type { Resource, RecordData, JournalLine } from "./types";
import { resourceByKey } from "../app/catalog";
import { blankRecord } from "./helpers";
export function createLinked(
  db: Database,
  key: string,
  source: RecordData,
  sourceKey: string,
  actor: string,
) {
  const r = resourceByKey(key);
  if (!r) throw new Error("후속 문서 유형이 없습니다.");
  const row = blankRecord(r, actor);
  let i = (db[key]?.length ?? 0) + 1;
  while (
    db[key]?.some(
      (x) => x.id === `${r.prefix}-2026-${String(i).padStart(4, "0")}`,
    )
  )
    i++;
  row.id = `${r.prefix}-2026-${String(i).padStart(4, "0")}`;
  row.title = source.title;
  row.partner = source.partner;
  row.department = source.department;
  row.amount = source.amount;
  row.date = source.date;
  row.dueDate = source.dueDate;
  row.links = [{ resource: sourceKey, id: source.id }];
  row.history = [
    {
      actor,
      at: new Date().toLocaleString("sv-SE", { timeZone: "Asia/Seoul" }),
      action: `${source.id}에서 생성`,
    },
  ];
  source.links.push({ resource: key, id: row.id });
  db[key] = [row, ...(db[key] ?? [])];
  return row;
}
export function ensureOpenPeriod(db: Database, date: string) {
  if (
    (db["fiscal-periods"] ?? []).some(
      (p) => p.status === "마감" && date >= p.date && date <= p.dueDate,
    )
  )
    throw new Error(
      "마감된 회계기간에는 전기할 수 없습니다. 기간을 재개하거나 전표일을 변경해 주세요.",
    );
}
export function postJournal(
  db: Database,
  source: RecordData,
  key: string,
  actor: string,
  journal: JournalLine[],
) {
  ensureOpenPeriod(db, source.date);
  const row = createLinked(db, "journals", source, key, actor);
  row.status = "전기완료";
  row.values.category =
    key === "payroll"
      ? "급여전표"
      : key === "invoices"
        ? "매출전표"
        : key === "purchase-bills"
          ? "매입전표"
          : "일반전표";
  row.journal = journal;
  row.amount = journal.reduce((n, l) => n + l.debit, 0);
  return row;
}
function twoLines(
  amount: number,
  debit: string,
  credit: string,
): JournalLine[] {
  return [
    { account: debit, debit: amount, credit: 0 },
    { account: credit, debit: 0, credit: amount },
  ];
}
export function adjustStock(
  db: Database,
  source: RecordData,
  key: string,
  item: string,
  warehouse: string,
  delta: number,
  actor: string,
) {
  let stock = db.stock?.find(
    (s) => s.title === item && s.values.warehouse === warehouse,
  );
  if (!stock) {
    if (delta < 0) throw new Error(`${warehouse}에 ${item} 재고가 없습니다.`);
    stock = createLinked(db, "stock", source, key, actor);
    stock.title = item;
    stock.values = {
      warehouse,
      qty: 0,
      reserved: 0,
      safety: 80,
      price: source.lines.find((l) => l.item === item)?.price ?? 120000,
    };
    stock.status = "정상";
  }
  const qty = Number(stock.values.qty) + delta;
  if (qty < Number(stock.values.reserved ?? 0))
    throw new Error(
      `${item} 가용재고가 부족합니다. 현재 가용: ${stock.values.available ?? stock.values.qty}`,
    );
  stock.values.qty = qty;
  stock.values.available = qty - Number(stock.values.reserved ?? 0);
  stock.amount = qty * Number(stock.values.price ?? 120000);
  stock.status =
    Number(stock.values.available) < Number(stock.values.safety ?? 0)
      ? "부족"
      : "정상";
  const ledger = createLinked(db, "stock-ledger", source, key, actor);
  ledger.title = item;
  ledger.status = "완료";
  ledger.values = {
    warehouse,
    movement: delta > 0 ? "입고" : "출고",
    inQty: Math.max(0, delta),
    outQty: Math.max(0, -delta),
    qty,
    reference: source.id,
  };
  ledger.amount = Math.abs(delta) * Number(stock.values.price ?? 0);
}
export function saveEffects(
  db: Database,
  r: Resource,
  row: RecordData,
  actor: string,
  isNew: boolean,
) {
  if (
    r.key === "stock-transfers" &&
    row.values.fromWarehouse === row.values.toWarehouse
  )
    throw new Error("출발 창고와 도착 창고는 달라야 합니다.");
  if (r.key === "payroll") {
    const gross = Number(row.values.gross),
      deduction = Number(row.values.tax) + Number(row.values.insurance);
    if (deduction > gross)
      throw new Error("공제액은 총급여를 초과할 수 없습니다.");
    row.values.deduction = deduction;
    row.values.net = gross - deduction;
    row.amount = gross - deduction;
  }
  if (r.key === "leave") {
    const diff =
      Math.round((Date.parse(row.dueDate) - Date.parse(row.date)) / 86400000) +
      1;
    if (Number(row.values.days) > diff)
      throw new Error("사용일수가 신청 기간을 초과합니다.");
    row.values.remaining = 15 - Number(row.values.days);
    if (Number(row.values.remaining) < 0)
      throw new Error("잔여 연차가 부족합니다.");
  }
  if (r.key === "approval-lines") {
    const names = [row.values.first, row.values.second, row.values.third];
    if (new Set(names).size !== names.length)
      throw new Error("결재선의 결재자는 중복 지정할 수 없습니다.");
  }
  if (
    r.key === "approval-policies" &&
    !db["approval-lines"].some(
      (x) => x.title === row.values.lineName && x.status === "활성",
    )
  )
    throw new Error("활성 결재선 이름을 입력해 주세요.");
  if (
    isPurchase(r.key) &&
    ["purchase-orders", "goods-receipts", "purchase-bills"].includes(r.key)
  ) {
    const previous = db[r.key]?.find((x) => x.id === row.id);
    const previousKey = previous ? sourceKey(r.key, previous) : "";
    if (
      previous &&
      (previous.values.sourceId !== row.values.sourceId ||
        previousKey !== sourceKey(r.key, row))
    ) {
      const old = db[previousKey]?.find(
        (x) => x.id === previous.values.sourceId,
      );
      if (old)
        old.links = old.links.filter(
          (l) => !(l.resource === r.key && l.id === row.id),
        );
      row.links = row.links.filter(
        (l) =>
          !(l.resource === previousKey && l.id === previous.values.sourceId),
      );
    }
  }
  if (r.source && r.kind !== "payment" && row.values.sourceId) {
    const sourceResource = isPurchase(r.key) ? sourceKey(r.key, row) : r.source;
    const source = db[sourceResource]?.find(
      (x) => x.id === row.values.sourceId,
    );
    if (!source) throw new Error("원천문서를 찾을 수 없습니다.");
    if (!row.links.some((l) => l.id === source.id))
      row.links.push({ resource: sourceResource, id: source.id });
    if (!source.links.some((l) => l.id === row.id))
      source.links.push({ resource: r.key, id: row.id });
  }
  if (r.kind === "approval" && isNew) {
    const policies = (db["approval-policies"] ?? [])
      .filter(
        (p) =>
          p.status === "활성" &&
          p.values.docType === row.values.docType &&
          Number(p.values.threshold) <= row.amount,
      )
      .sort((a, b) => Number(b.values.threshold) - Number(a.values.threshold));
    const line = (db["approval-lines"] ?? []).find(
      (l) => l.title === policies[0]?.values.lineName && l.status === "활성",
    );
    if (line) {
      const people = [line.values.first, line.values.second, line.values.third]
        .map(String)
        .filter((n) => n && n !== actor);
      row.approvals = [
        { name: actor, role: "기안", status: "기안" },
        ...people.map((name, i) => ({
          name,
          role: i === people.length - 1 ? "최종 승인" : `${i + 1}차 결재`,
          status: "예정" as const,
        })),
      ];
    }
  }
  if (r.kind === "payment" && isNew) {
    const allocations = JSON.parse(
      String(row.values.allocations ?? "{}"),
    ) as Record<string, number>;
    const entries = Object.entries(allocations).filter(([, n]) => n > 0);
    if (
      !entries.length ||
      entries.reduce((n, [, v]) => n + v, 0) !== row.amount
    )
      throw new Error("문서별 배분 합계가 지급/수금액과 같아야 합니다.");
    ensureOpenPeriod(db, row.date);
    for (const [id, amount] of entries) {
      const source = db[r.source!]?.find((x) => x.id === id);
      if (
        !source ||
        source.partner !== row.partner ||
        !Number.isSafeInteger(amount) ||
        amount <= 0 ||
        amount > Number(source.values.balance)
      )
        throw new Error(
          "같은 거래처의 미결제 문서에 잔액 이내로 배분해 주세요.",
        );
      source.values.balance = Number(source.values.balance) - amount;
      source.status =
        Number(source.values.balance) === 0
          ? r.key === "payments-out"
            ? "지급완료"
            : "수금완료"
          : r.key === "payments-out"
            ? "부분지급"
            : "부분수금";
      source.links.push({ resource: r.key, id: row.id });
      row.links.push({ resource: r.source!, id });
      source.history.push({
        actor,
        at: row.date,
        action: `${row.id} ${amount.toLocaleString()}원 배분`,
      });
    }
    postJournal(
      db,
      row,
      r.key,
      actor,
      twoLines(
        row.amount,
        r.key === "payments-out" ? "2110 매입채무" : "1010 보통예금",
        r.key === "payments-out" ? "1010 보통예금" : "1130 매출채권",
      ),
    );
  }
}
export function transitionEffects(
  db: Database,
  r: Resource,
  row: RecordData,
  action: string,
  actor: string,
) {
  if (r.key === "journals" && action === "전표 전기")
    ensureOpenPeriod(db, row.date);
  if (r.key === "journals" && action === "역분개 생성") {
    ensureOpenPeriod(db, "2026-09-29");
    const reversal = createLinked(db, "journals", row, "journals", actor);
    reversal.date = "2026-09-29";
    reversal.title = `[역분개] ${row.title}`;
    reversal.status = "전기완료";
    reversal.values.category = "역분개";
    reversal.journal = row.journal.map((l) => ({
      ...l,
      debit: l.credit,
      credit: l.debit,
    }));
    reversal.amount = row.amount;
  }
  if (
    r.key === "fiscal-periods" &&
    action === "기간 마감" &&
    db.journals?.some(
      (j) =>
        j.status === "작성중" && j.date >= row.date && j.date <= row.dueDate,
    )
  )
    throw new Error("미전기 전표가 있어 기간을 마감할 수 없습니다.");
  if (r.key === "stock-transfers" && action === "이동 확정") {
    if (row.values.fromWarehouse === row.values.toWarehouse)
      throw new Error("출발 창고와 도착 창고는 달라야 합니다.");
    adjustStock(
      db,
      row,
      r.key,
      row.title,
      String(row.values.fromWarehouse),
      -Number(row.values.qty),
      actor,
    );
    adjustStock(
      db,
      row,
      r.key,
      row.title,
      String(row.values.toWarehouse),
      Number(row.values.qty),
      actor,
    );
  }
  if (r.key === "stock-adjustments" && action === "조정 확정")
    adjustStock(
      db,
      row,
      r.key,
      row.title,
      String(row.values.warehouse),
      Number(row.values.qty) * (row.values.direction === "감소" ? -1 : 1),
      actor,
    );
  if (
    (r.key === "goods-receipts" && action === "입고 확정") ||
    (r.key === "deliveries" && action === "출고 확정")
  ) {
    if (r.key === "goods-receipts") validatePurchase(r.key, row, db);
    for (const l of row.lines)
      adjustStock(
        db,
        row,
        r.key,
        l.item,
        String(row.values.warehouse ?? "본사 자재창고"),
        l.qty * (r.key === "deliveries" ? -1 : 1),
        actor,
      );
    for (const link of row.links) {
      const parent = db[link.resource]?.find((x) => x.id === link.id);
      if (
        parent &&
        ((r.key === "goods-receipts" && link.resource === "purchase-orders") ||
          (r.key === "deliveries" && link.resource === "sales-orders"))
      ) {
        if (r.key === "goods-receipts") {
          const progress = receiptProgress(parent, db["goods-receipts"] || []);
          parent.status = progress.every((l) => l.remaining === 0)
            ? "입고완료"
            : progress.some((l) => l.received > 0)
              ? "부분입고"
              : "확정";
        } else parent.status = "출고완료";
      }
    }
  }
  if (r.key === "purchase-bills" && action === "매입 전기") {
    const total = purchaseTotals(row);
    row.values.netAmount = total.net;
    row.values.taxAmount = total.tax;
    row.values.grossAmount = total.gross;
    postJournal(db, row, r.key, actor, [
      { account: "1310 원재료", debit: total.net, credit: 0 },
      ...(total.tax
        ? [{ account: "1350 부가세대급금", debit: total.tax, credit: 0 }]
        : []),
      { account: "2110 매입채무", debit: 0, credit: total.gross },
    ]);
  }
  if (r.key === "invoices" && action === "매출 전기")
    postJournal(
      db,
      row,
      r.key,
      actor,
      twoLines(row.amount, "1130 매출채권", "4010 제품매출"),
    );
  if (r.key === "payroll" && action === "급여 지급 · 전기")
    postJournal(db, row, r.key, actor, [
      { account: "6010 급여", debit: Number(row.values.gross), credit: 0 },
      { account: "1010 보통예금", debit: 0, credit: Number(row.values.net) },
      {
        account: "2120 예수금",
        debit: 0,
        credit: Number(row.values.deduction),
      },
    ]);
  if (r.key === "work-orders" && action === "자재 출고 · 생산 시작") {
    const bom = db.boms?.find((b) => b.id === row.values.bom);
    if (!bom) throw new Error("유효한 BOM 번호를 지정해 주세요.");
    row.lines = bom.lines.map((l) => ({
      ...l,
      qty: (l.qty * Number(row.values.qty)) / Number(bom.values.outputQty ?? 1),
    }));
    for (const l of row.lines)
      adjustStock(db, row, r.key, l.item, "화성 생산창고", -l.qty, actor);
    row.values.progress = 10;
    row.values.produced = 0;
    row.amount = row.lines.reduce((n, l) => n + l.qty * l.price, 0);
    postJournal(
      db,
      row,
      r.key,
      actor,
      twoLines(row.amount, "1340 재공품", "1310 원재료"),
    );
  }
  if (r.key === "work-orders" && action === "생산 완료") {
    row.values.progress = 100;
    row.values.produced = Number(row.values.qty);
  }
  if (r.key === "production-receipts" && action === "완제품 입고 확정") {
    adjustStock(
      db,
      row,
      r.key,
      row.title,
      String(row.values.warehouse ?? "화성 생산창고"),
      Number(row.values.qty),
      actor,
    );
    postJournal(
      db,
      row,
      r.key,
      actor,
      twoLines(row.amount, "1330 제품", "1340 재공품"),
    );
  }
  if (r.key === "sales-returns" && action === "반품 확정") {
    for (const l of row.lines)
      adjustStock(
        db,
        row,
        r.key,
        l.item,
        String(row.values.warehouse ?? "본사 자재창고"),
        l.qty,
        actor,
      );
    postJournal(
      db,
      row,
      r.key,
      actor,
      twoLines(row.amount, "4010 제품매출", "1130 매출채권"),
    );
  }
}
