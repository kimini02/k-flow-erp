import type { RecordData } from "../../domain/types";

export function agingSummary(rows: RecordData[]) {
  const groups = ["미연체", "1–30일", "31–60일", "61–90일", "90일 초과"].map(
    (name) => ({ name, value: 0 }),
  );
  for (const row of rows) {
    const balance = Number(row.values.balance ?? 0);
    if (balance <= 0) continue;
    const days = Number(row.values.aging ?? 0);
    const index =
      days <= 0 ? 0 : days <= 30 ? 1 : days <= 60 ? 2 : days <= 90 ? 3 : 4;
    groups[index].value += balance;
  }
  return { groups, total: groups.reduce((n, g) => n + g.value, 0) };
}

// Mirrors the existing accounting screen's demo account-code classification.
// AR/AP are current snapshots; they cannot be reconstructed for a past as-of date.
export function financeOverviewModel(
  journals: RecordData[],
  receivables: RecordData[],
  payables: RecordData[],
  asOf: string,
) {
  const posted = journals.filter(
    (r) => ["전기완료", "역분개완료"].includes(r.status) && r.date <= asOf,
  );
  const period = posted.filter((r) => r.date >= "2026-01-01");
  const entries = period.flatMap((r) => r.journal);
  const net = (prefix: string) =>
    entries
      .filter((l) => l.account.startsWith(prefix))
      .reduce((n, l) => n + l.credit - l.debit, 0);
  const revenue = net("4"),
    cost = -net("5") || 0,
    expenses = -net("6") || 0,
    income = revenue - cost - expenses;
  const [year, month] = asOf.split("-").map(Number);
  const months = Array.from({ length: 6 }, (_, i) => {
    const date = new Date(Date.UTC(year, month - 6 + i, 1));
    return {
      month: date.toISOString().slice(0, 7),
      revenue: 0,
      grossProfit: 0,
      inflow: 0,
      outflow: 0,
    };
  });
  const byMonth = new Map(months.map((m) => [m.month, m]));
  for (const r of posted) {
    const point = byMonth.get(r.date.slice(0, 7));
    if (!point) continue;
    for (const l of r.journal) {
      if (l.account.startsWith("4")) {
        point.revenue += l.credit - l.debit;
        point.grossProfit += l.credit - l.debit;
      }
      if (l.account.startsWith("5")) point.grossProfit -= l.debit - l.credit;
      if (l.account.startsWith("1010 ")) {
        point.inflow += l.debit;
        point.outflow += l.credit;
      }
    }
  }
  return {
    ar: agingSummary(receivables),
    ap: agingSummary(payables),
    revenue,
    cost,
    expenses,
    income,
    assets: -net("1") || 0,
    equity: net("3") + income,
    cash: -net("1010 ") || 0,
    debit: entries.reduce((n, l) => n + l.debit, 0),
    credit: entries.reduce((n, l) => n + l.credit, 0),
    postedCount: period.length,
    months,
    profit: [
      { name: "매출액", value: revenue },
      { name: "비용 합계", value: cost + expenses },
      { name: "당기순이익", value: income },
    ],
  };
}
