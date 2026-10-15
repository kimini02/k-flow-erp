import type { RecordData } from "../../domain/types";
const COMPONENT_COST: Record<string, number> = {
  "PCB 기판 Type-A": 45000,
  "알루미늄 하우징": 38000,
};
// DR-01 (adopted): only K-100 is produced in-house and its inputs are purchased raw
// materials/parts. S-200 and M-50 are resale goods, so they have no BOM, work order or
// production receipt. Single-level only; multi-level BOM stays open (DR-04/18).
const PRODUCT = "정밀 제어 모듈 K-100";
const BOM_RECIPES: {
  version: string;
  active: boolean;
  scrap: number;
  lines: [string, number][];
}[] = [
  {
    version: "v2.0",
    active: true,
    scrap: 2,
    lines: [
      ["PCB 기판 Type-A", 2],
      ["알루미늄 하우징", 1],
    ],
  },
  {
    version: "v1.0",
    active: false,
    scrap: 3,
    lines: [
      ["PCB 기판 Type-A", 3],
      ["알루미늄 하우징", 2],
    ],
  },
  {
    version: "v1.1",
    active: false,
    scrap: 2,
    lines: [
      ["PCB 기판 Type-A", 2],
      ["알루미늄 하우징", 2],
    ],
  },
];
export function manufacturingFixture(key: string, r: RecordData, i: number) {
  r.partner = "";
  r.title = PRODUCT;
  r.values.warehouse = "화성 생산창고";
  if (key === "boms") {
    // Single-level BOMs (multi-level explosion is DR-04/18): one active version per
    // product; work orders keep pointing at the version they were planned with.
    const recipe = BOM_RECIPES[i % BOM_RECIPES.length];
    r.values.version = recipe.version;
    r.values.outputQty = 1;
    r.values.scrap = recipe.scrap;
    r.lines = recipe.lines.map(([item, qty]) => ({
      item,
      qty,
      price: COMPONENT_COST[item],
    }));
    r.amount = r.lines.reduce((n, l) => n + l.qty * l.price, 0);
    r.status = recipe.active ? "사용중" : "비활성";
  }
  if (key === "work-orders") {
    r.values.line = ["A라인 · 조립", "B라인 · 가공", "C라인 · 검사"][i % 3];
    r.values.qty = 100 + (i % 5) * 50;
    r.status = ["생산중", "확정", "생산완료", "작성중"][i % 4];
    // Open and running orders use the active BOM; finished ones keep older versions.
    r.values.bom =
      r.status === "생산완료"
        ? `BOM-2026-000${2 + (Math.floor(i / 4) % 2)}`
        : "BOM-2026-0001";
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
