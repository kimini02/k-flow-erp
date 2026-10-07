import type { RecordData } from "../../domain/types";
import { ITEMS } from "../../domain/types";
export function manufacturingFixture(key: string, r: RecordData, i: number) {
  r.partner = "";
  r.title = ITEMS[i % 2];
  r.values.warehouse = "화성 생산창고";
  if (key === "boms") {
    r.values.version = `v${1 + (i % 3)}.0`;
    r.values.outputQty = 1;
    r.values.scrap = 2;
    r.lines = [
      { item: "PCB 기판 Type-A", qty: 1, price: 45000 },
      { item: "알루미늄 하우징", qty: 1, price: 38000 },
      { item: "산업용 센서 S-200", qty: 2, price: 24000 },
    ];
    r.amount = 131000;
    r.status = "사용중";
  }
  if (key === "work-orders") {
    r.values.bom = `BOM-2026-${String((i % 6) + 1).padStart(4, "0")}`;
    r.values.line = ["A라인 · 조립", "B라인 · 가공", "C라인 · 검사"][i % 3];
    r.values.qty = 100 + (i % 5) * 50;
    r.status = ["생산중", "확정", "생산완료", "작성중"][i % 4];
    r.values.progress =
      r.status === "생산중"
        ? 45 + (i % 4) * 8
        : r.status === "생산완료"
          ? 100
          : 0;
    r.values.produced = Math.floor(
      (Number(r.values.qty) * Number(r.values.progress)) / 100,
    );
    r.lines = [
      { item: "PCB 기판 Type-A", qty: 100, price: 45000 },
      { item: "알루미늄 하우징", qty: 100, price: 38000 },
    ];
    r.amount = Number(r.values.qty) * 131000;
  }
  if (key === "material-issues") {
    r.lines = [
      { item: "PCB 기판 Type-A", qty: 100, price: 45000 },
      { item: "알루미늄 하우징", qty: 100, price: 38000 },
    ];
    r.amount = 8300000;
  }
  if (key === "production-receipts") {
    r.values.qty = 100 + i * 10;
    r.amount = Number(r.values.qty) * 131000;
  }
}
