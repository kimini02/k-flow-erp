import type { RecordData } from "../../domain/types";
import { ITEMS } from "../../domain/types";
const COMPONENT_COST: Record<string, number> = {
  "PCB 기판 Type-A": 45000,
  "알루미늄 하우징": 38000,
  "산업용 센서 S-200": 83000,
  "서보 모터 M-50": 121000,
};
// Index order keeps BOM-0001..0006 on the products their work orders produce.
const BOM_RECIPES: {
  product: string;
  version: string;
  active: boolean;
  scrap: number;
  lines: [string, number][];
}[] = [
  {
    product: "정밀 제어 모듈 K-100",
    version: "v2.0",
    active: true,
    scrap: 2,
    lines: [
      ["PCB 기판 Type-A", 2],
      ["알루미늄 하우징", 1],
      ["서보 모터 M-50", 1],
      ["산업용 센서 S-200", 2],
    ],
  },
  {
    product: "산업용 센서 S-200",
    version: "v2.0",
    active: true,
    scrap: 1,
    lines: [
      ["PCB 기판 Type-A", 1],
      ["알루미늄 하우징", 1],
    ],
  },
  {
    product: "정밀 제어 모듈 K-100",
    version: "v1.0",
    active: false,
    scrap: 3,
    lines: [
      ["PCB 기판 Type-A", 2],
      ["알루미늄 하우징", 1],
      ["산업용 센서 S-200", 2],
    ],
  },
  {
    product: "산업용 센서 S-200",
    version: "v1.0",
    active: false,
    scrap: 2,
    lines: [
      ["PCB 기판 Type-A", 2],
      ["알루미늄 하우징", 1],
    ],
  },
  {
    product: "정밀 제어 모듈 K-100",
    version: "v1.1",
    active: false,
    scrap: 2,
    lines: [
      ["PCB 기판 Type-A", 2],
      ["알루미늄 하우징", 2],
      ["산업용 센서 S-200", 2],
    ],
  },
  {
    product: "산업용 센서 S-200",
    version: "v1.1",
    active: false,
    scrap: 1,
    lines: [
      ["PCB 기판 Type-A", 1],
      ["알루미늄 하우징", 2],
    ],
  },
  {
    product: "서보 모터 M-50",
    version: "v1.0",
    active: true,
    scrap: 1,
    lines: [
      ["PCB 기판 Type-A", 1],
      ["알루미늄 하우징", 2],
    ],
  },
];
export function manufacturingFixture(key: string, r: RecordData, i: number) {
  r.partner = "";
  r.title = ITEMS[i % 2];
  r.values.warehouse = "화성 생산창고";
  if (key === "boms") {
    // Single-level BOMs (multi-level explosion is DR-04/18): one active version per
    // product; work orders keep pointing at the version they were planned with.
    const recipe = BOM_RECIPES[i % BOM_RECIPES.length];
    r.title = recipe.product;
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
