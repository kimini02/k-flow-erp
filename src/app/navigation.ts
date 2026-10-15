import {
  IconLayoutDashboard,
  IconFileCheck,
  IconShoppingCart,
  IconBox,
  IconTruckDelivery,
  IconBuildingFactory2,
  IconBook2,
  IconUsers,
  IconChartBar,
  IconShieldCheck,
} from "@tabler/icons-react";
import { modules } from "./catalog";

export type NavLink = { to: string; label: string };
export type NavSection = { title?: string; links: NavLink[] };
export type RailItem = {
  key: string;
  label: string;
  short: string;
  icon: typeof IconBox;
  sections: NavSection[];
};

// Sub-menu grouping per module; screens not listed here fall into a last section.
const sectionPlan: Record<string, [string | undefined, string[]][]> = {
  approval: [
    ["결재함", ["inbox", "pending", "completed", "rejected"]],
    ["기안", ["drafts"]],
    ["관리", ["lines", "policies"]],
  ],
  purchasing: [
    ["요청 · 발주", ["requests", "orders"]],
    ["입고 · 매입", ["receipts", "bills"]],
    ["채무 · 지급", ["payables", "payments"]],
  ],
  inventory: [
    ["재고 조회", ["stock", "shortage", "ledger"]],
    ["재고 처리", ["transfers", "adjustments"]],
    ["기준정보", ["items", "warehouses"]],
  ],
  sales: [
    ["견적 · 주문", ["quotes", "orders"]],
    ["출고 · 매출", ["deliveries", "invoices", "returns"]],
    ["채권 · 수금", ["receivables", "receipts"]],
  ],
  manufacturing: [
    ["생산 계획", ["boms", "orders"]],
    ["생산 실행", ["issues", "progress", "receipts"]],
    ["현황", ["overview"]],
  ],
  accounting: [
    ["전표", ["journals", "reversals"]],
    ["장부 · 재무제표", ["ledger", "trial-balance", "income", "balance-sheet"]],
    ["기준정보", ["accounts", "periods"]],
  ],
  hr: [
    ["인사", ["employees", "departments", "positions"]],
    ["근태 · 급여", ["attendance", "leave", "payroll"]],
  ],
};

const railMeta: Record<string, { short: string; icon: typeof IconBox }> = {
  approval: { short: "결재", icon: IconFileCheck },
  purchasing: { short: "구매", icon: IconShoppingCart },
  inventory: { short: "재고", icon: IconBox },
  sales: { short: "영업", icon: IconTruckDelivery },
  manufacturing: { short: "생산", icon: IconBuildingFactory2 },
  accounting: { short: "회계", icon: IconBook2 },
  hr: { short: "인사", icon: IconUsers },
  reporting: { short: "보고서", icon: IconChartBar },
  audit: { short: "감사", icon: IconShieldCheck },
};

function moduleSections(key: string): NavSection[] {
  const mod = modules.find((m) => m.key === key)!;
  const link = (item: string) => {
    const found = mod.items.find((i) => i.key === item);
    return found && { to: `/${mod.key}/${found.key}`, label: found.label };
  };
  const plan = sectionPlan[key] ?? [[undefined, mod.items.map((i) => i.key)]];
  const listed = new Set(plan.flatMap(([, items]) => items));
  const rest = mod.items.filter((i) => !listed.has(i.key)).map((i) => i.key);
  const extra: [string, string[]][] = rest.length ? [["기타", rest]] : [];
  return [...plan, ...extra].map(
    ([title, items]) => ({
      title,
      links: items.map(link).filter((l): l is NavLink => !!l),
    }),
  );
}

export const homeKey = "home";
export const railItems: RailItem[] = [
  {
    key: homeKey,
    label: "홈",
    short: "홈",
    icon: IconLayoutDashboard,
    sections: [
      { links: [{ to: "/", label: "대시보드" }] },
      {
        title: "바로가기",
        links: [
          { to: "/approval/pending", label: "결재대기" },
          { to: "/purchasing/requests", label: "구매요청(PR)" },
          { to: "/sales/orders", label: "판매주문(SO)" },
          { to: "/inventory/stock", label: "재고현황" },
          { to: "/manufacturing/orders", label: "작업지시" },
        ],
      },
    ],
  },
  ...modules.map((m) => ({
    key: m.key,
    label: m.label,
    short: railMeta[m.key]?.short ?? m.label,
    icon: railMeta[m.key]?.icon ?? IconBox,
    sections: moduleSections(m.key),
  })),
];

export const railKeyFor = (pathname: string) => {
  const moduleKey = pathname.split("/")[1];
  return railItems.some((r) => r.key === moduleKey) ? moduleKey : homeKey;
};
