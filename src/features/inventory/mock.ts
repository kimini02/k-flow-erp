import type { RecordData } from "../../domain/types";
import { ITEMS, WAREHOUSES } from "../../domain/types";
export function inventoryFixture(key: string, r: RecordData, i: number) {
  r.partner = "";
  if (
    [
      "stock",
      "items",
      "stock-ledger",
      "stock-transfers",
      "stock-adjustments",
    ].includes(key)
  )
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
  if (key === "stock-ledger") {
    r.values.movement = ["구매입고", "판매출고", "생산투입", "생산완료"][i % 4];
    r.values.inQty = i % 2 === 0 ? 100 : 0;
    r.values.outQty = i % 2 ? 30 : 0;
    r.values.qty = 250 + i * 10;
    r.values.reference = `${i % 2 ? "DO" : "GR"}-2026-${String(i + 1).padStart(4, "0")}`;
    r.status = "완료";
    r.links = [
      {
        resource: i % 2 ? "deliveries" : "goods-receipts",
        id: String(r.values.reference),
      },
    ];
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
