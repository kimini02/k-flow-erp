import type { Line, RecordData } from "../../domain/types";
import { PARTNERS, DEMO_DATE } from "../../domain/types";
import type { Database } from "../../api/seed";

export interface Partner {
  id: string;
  name: string;
  businessNumber: string;
  type: "공급업체";
  paymentTerms: string;
  status: "사용중" | "비활성";
}
// Independent Mock master, not a backend entity or a REST contract.
export const mockPartners: Partner[] = PARTNERS.map((name, i) => ({
  id: `PARTNER-${String(i + 1).padStart(3, "0")}`,
  name,
  businessNumber: "",
  type: "공급업체",
  paymentTerms: "증빙일로부터 30일",
  status: "사용중",
}));
export const isPurchase = (key: string) =>
  [
    "purchase-requests",
    "purchase-orders",
    "goods-receipts",
    "purchase-bills",
    "payables",
    "payments-out",
  ].includes(key);
export const sourceKey = (key: string, row: RecordData) =>
  String(
    row.values.sourceResource ||
      (
        {
          "purchase-orders": "purchase-requests",
          "goods-receipts": "purchase-orders",
          "purchase-bills": "goods-receipts",
        } as Record<string, string>
      )[key] ||
      "",
  );
export const lineId = (row: RecordData, i: number) =>
  row.lines[i].id || `${row.id}:line:${i + 1}`;
export function itemSnapshot(item: RecordData): Partial<Line> {
  return {
    itemId: item.id,
    itemCode: String(item.values.code || item.id),
    item: item.title,
    itemType: String(item.values.category || ""),
    specification: String(item.values.specification || item.values.spec || "—"),
    unit: String(item.values.unit || "EA"),
    price: Number(item.values.price || 0),
  };
}
export function purchaseTotals(row: RecordData) {
  const net = row.lines.reduce((n, l) => n + Math.round(l.qty * l.price), 0);
  const tax =
    row.values.taxType === "일반과세 10%"
      ? row.lines.reduce(
          (n, l) => n + Math.round(Math.round(l.qty * l.price) * 0.1),
          0,
        )
      : 0;
  return { net, tax, gross: net + tax };
}
export function receiptProgress(
  po: RecordData,
  receipts: RecordData[],
  excludeId = "",
) {
  return po.lines.map((l, i) => {
    const id = lineId(po, i);
    const received = receipts
      .filter(
        (r) =>
          r.id !== excludeId &&
          r.status === "입고완료" &&
          r.values.sourceId === po.id,
      )
      .reduce(
        (n, r) =>
          n +
          r.lines
            .filter((x) => x.sourceLineId === id)
            .reduce((s, x) => s + x.qty, 0),
        0,
      );
    return {
      ...l,
      id,
      received: Math.round(received * 1000) / 1000,
      remaining: Math.max(0, Math.round((l.qty - received) * 1000) / 1000),
    };
  });
}
export function fromPurchaseSource(
  key: string,
  draft: RecordData,
  source: RecordData,
  resource: string,
  receipts: RecordData[] = [],
): RecordData {
  const lines =
    key === "goods-receipts"
      ? receiptProgress(source, receipts).map((l) => ({
          ...l,
          id: undefined,
          sourceLineId: l.id,
          qty: l.remaining,
        }))
      : source.lines.map((l, i) => ({
          ...l,
          id: undefined,
          sourceLineId: lineId(source, i),
        }));
  return {
    ...draft,
    title:
      key === "purchase-orders"
        ? source.title
        : `${source.id} ${key === "goods-receipts" ? "입고" : "매입"}`,
    partner: source.partner,
    lines,
    amount: lines.reduce(
      (total, line) => total + Math.round(line.qty * line.price),
      0,
    ),
    values: {
      ...draft.values,
      sourceId: source.id,
      sourceResource: resource,
      partnerId: source.values.partnerId || "",
      warehouse: source.values.warehouse || draft.values.warehouse || "",
      paymentTerms:
        source.values.paymentTerms || draft.values.paymentTerms || "",
      taxType: source.values.taxType || draft.values.taxType || "일반과세 10%",
    },
  };
}
export function normalizePurchasing(db: Database) {
  for (const key of [
    "purchase-requests",
    "purchase-orders",
    "goods-receipts",
    "purchase-bills",
    "payables",
    "payments-out",
  ]) {
    for (const row of db[key] || []) {
      if (!row.values.partnerId)
        row.values.partnerId =
          mockPartners.find((p) => p.name === row.partner)?.id || "";
      row.lines.forEach((l, i) => {
        l.id ||= `${row.id}:line:${i + 1}`;
        const item = db.items?.find(
          (x) => x.id === l.itemId || (!l.itemId && x.title === l.item),
        );
        if (item) {
          const snapshot = itemSnapshot(item);
          l.itemId ||= snapshot.itemId;
          l.itemCode ||= snapshot.itemCode;
          l.itemType ||= snapshot.itemType;
          l.specification ||= snapshot.specification;
          l.unit ||= snapshot.unit;
        }
      });
      const parentKey = sourceKey(key, row);
      if (parentKey && !row.values.sourceId) {
        const link = row.links.find((x) => x.resource === parentKey);
        if (link) {
          row.values.sourceId = link.id;
          row.values.sourceResource = parentKey;
        }
      }
      const parent = db[parentKey]?.find((x) => x.id === row.values.sourceId);
      if (parent)
        row.lines.forEach((l, i) => {
          if (!l.sourceLineId && parent.lines[i]?.item === l.item)
            l.sourceLineId = lineId(parent, i);
        });
      if (key === "purchase-bills" && row.values.grossAmount === undefined)
        row.values.grossAmount = row.amount; // Preserve historic mock amounts; do not silently add VAT.
      if (key === "payables") refreshPayable(row);
    }
  }
}
export function refreshPayable(row: RecordData) {
  row.values.billId =
    row.links.find((l) => l.resource === "purchase-bills")?.id ||
    row.values.billId ||
    "";
  row.values.paidAmount = row.amount - Number(row.values.balance || 0);
  const today = new Date().toLocaleDateString("sv-SE", {
    timeZone: "Asia/Seoul",
  });
  row.values.aging =
    Number(row.values.balance) > 0
      ? Math.max(
          0,
          Math.floor((Date.parse(today) - Date.parse(row.dueDate)) / 86400000),
        ) || 0
      : 0;
  row.status =
    Number(row.values.balance) === 0
      ? "지급완료"
      : Number(row.values.aging) > 0
        ? "연체"
        : Number(row.values.paidAmount) > 0
          ? "부분지급"
          : "미지급";
}
export function initializePurchase(key: string, row: RecordData): RecordData {
  const result = structuredClone(row);
  result.values.taxType ||= "일반과세 10%";
  result.values.paymentTerms ||= "증빙일로부터 30일";
  if (key === "purchase-bills")
    result.values.evidenceDate ||= result.date || DEMO_DATE;
  if (key === "goods-receipts" && !result.values.sourceId) result.lines = [];
  if (key === "purchase-bills" && !result.values.sourceId) result.lines = [];
  return result;
}
export function validatePurchase(key: string, row: RecordData, db: Database) {
  if (
    [
      "purchase-requests",
      "purchase-orders",
      "goods-receipts",
      "purchase-bills",
    ].includes(key) &&
    row.lines.some(
      (l) => !db.items?.some((i) => i.id === l.itemId && i.status === "사용중"),
    )
  )
    throw new Error("사용 가능한 품목을 선택해 주세요.");
  if (
    row.partner &&
    !mockPartners.some(
      (p) =>
        p.name === row.partner &&
        (!row.values.partnerId || p.id === row.values.partnerId),
    )
  )
    throw new Error("유효한 거래처를 선택해 주세요.");
  if (
    ["purchase-orders", "purchase-bills"].includes(key) &&
    !["일반과세 10%", "면세 0%"].includes(String(row.values.taxType))
  )
    throw new Error("과세유형을 선택해 주세요.");
  if (
    ["purchase-orders", "goods-receipts"].includes(key) &&
    !db.warehouses?.some(
      (w) => w.title === row.values.warehouse && w.status === "사용중",
    )
  )
    throw new Error("입고창고를 선택해 주세요.");
  if (key === "purchase-bills" && row.values.evidenceDate > row.dueDate)
    throw new Error("지급기한은 증빙일 이후여야 합니다.");
  if (key === "purchase-orders" && row.values.sourceId) {
    const pr = db["purchase-requests"]?.find(
      (x) => x.id === row.values.sourceId,
    );
    if (!pr || !["승인완료", "발주완료"].includes(pr.status))
      throw new Error("승인된 구매요청을 선택해 주세요.");
  }
  if (key === "goods-receipts" || key === "purchase-bills") {
    const resource = sourceKey(key, row);
    if (
      !(
        key === "goods-receipts"
          ? ["purchase-orders"]
          : ["goods-receipts", "purchase-orders"]
      ).includes(resource)
    )
      throw new Error("지원하는 원천문서를 선택해 주세요.");
    const source = db[resource]?.find((x) => x.id === row.values.sourceId);
    if (!source) throw new Error("원천문서를 선택해 주세요.");
    if (
      !(
        resource === "purchase-orders"
          ? ["확정", "부분입고", "입고완료"]
          : ["입고완료"]
      ).includes(source.status)
    )
      throw new Error("확정된 원천문서만 사용할 수 있습니다.");
    if (source.partner !== row.partner)
      throw new Error("거래처가 원천문서와 다릅니다.");
    const ids = row.lines.map((l) => l.sourceLineId);
    if (new Set(ids).size !== ids.length)
      throw new Error("같은 원천 행을 중복 처리할 수 없습니다.");
    const progress = receiptProgress(
      source,
      db["goods-receipts"] || [],
      row.id,
    );
    for (const l of row.lines) {
      const original = source.lines.find(
        (_, i) => lineId(source, i) === l.sourceLineId,
      );
      if (!original || original.itemId !== l.itemId)
        throw new Error("원천 품목 행이 일치하지 않습니다.");
      if (
        key === "goods-receipts" &&
        l.qty > (progress.find((x) => x.id === l.sourceLineId)?.remaining || 0)
      )
        throw new Error("이번 입고수량이 잔여수량을 초과합니다.");
      if (key === "purchase-bills") {
        const billed = (db["purchase-bills"] || [])
          .filter(
            (b) =>
              b.id !== row.id &&
              b.status === "전기완료" &&
              sourceKey(key, b) === resource &&
              b.values.sourceId === source.id,
          )
          .reduce(
            (n, b) =>
              n +
              b.lines
                .filter((x) => x.sourceLineId === l.sourceLineId)
                .reduce((s, x) => s + x.qty, 0),
            0,
          );
        if (l.qty + billed > original.qty)
          throw new Error("매입수량이 원천의 미매입수량을 초과합니다.");
      }
    }
  }
}
