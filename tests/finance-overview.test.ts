import { describe, it, expect } from "vitest";
import type { RecordData } from "../src/domain/types";
import {
  agingSummary,
  financeOverviewModel,
} from "../src/features/reporting/financeOverviewModel";
const row = (partial: Partial<RecordData>): RecordData => ({
  id: "1",
  title: "",
  status: "전기완료",
  date: "2026-09-10",
  dueDate: "",
  owner: "",
  partner: "",
  department: "",
  amount: 0,
  values: {},
  lines: [],
  journal: [],
  approvals: [],
  links: [],
  history: [],
  ...partial,
});
const sale = row({
  journal: [
    { account: "1130 매출채권", debit: 100, credit: 0 },
    { account: "4010 제품매출", debit: 0, credit: 100 },
  ],
});
describe("재무 개요의 집계 범위", () => {
  it("미전기·미래 전표를 제외하고 원전표와 역분개를 함께 집계한다", () => {
    const reversal = row({
      status: "전기완료",
      journal: sale.journal.map((l) => ({
        ...l,
        debit: l.credit,
        credit: l.debit,
      })),
    });
    const result = financeOverviewModel(
      [
        { ...sale, status: "역분개완료" },
        reversal,
        { ...sale, status: "작성중" },
        { ...sale, date: "2026-10-01" },
      ],
      [],
      [],
      "2026-09-30",
    );
    expect(result.income).toBe(0);
    expect(result.assets).toBe(0);
    expect(result.postedCount).toBe(2);
    expect(result.debit).toBe(result.credit);
  });
  it("기준일 이후의 금액이 손익 및 월별 추이에 섞이지 않는다", () => {
    const result = financeOverviewModel(
      [sale, { ...sale, date: "2026-09-20" }],
      [],
      [],
      "2026-09-15",
    );
    expect(result.revenue).toBe(100);
    expect(result.months.reduce((n, m) => n + m.revenue, 0)).toBe(100);
  });
  it("부분 결제 후 남은 잔액을 연체 구간 경계에 정확히 배분한다", () => {
    const rows = [0, 30, 31, 60, 61, 90, 91].map((days) =>
      row({ amount: 1000, values: { aging: days, balance: 10 } }),
    );
    const result = agingSummary([
      ...rows,
      row({ values: { aging: 100, balance: 0 } }),
    ]);
    expect(result.groups.map((g) => g.value)).toEqual([10, 10, 20, 20, 10]);
    expect(result.total).toBe(70);
  });
  it("연도 경계를 넘는 6개월 추이와 2026년 재무 누계를 구분한다", () => {
    const result = financeOverviewModel(
      [
        { ...sale, date: "2025-12-10" },
        { ...sale, date: "2026-01-10" },
      ],
      [],
      [],
      "2026-01-31",
    );
    expect(result.months.map((m) => m.month)).toEqual([
      "2025-08",
      "2025-09",
      "2025-10",
      "2025-11",
      "2025-12",
      "2026-01",
    ]);
    expect(result.revenue).toBe(100);
    expect(result.months.reduce((n, m) => n + m.revenue, 0)).toBe(200);
  });
  it("보통예금 전표의 유입과 유출을 구분하며 차이가 잔액과 일치한다", () => {
    const result = financeOverviewModel(
      [
        row({
          journal: [
            { account: "1010 보통예금", debit: 500, credit: 0 },
            { account: "3010 자본금", debit: 0, credit: 500 },
          ],
        }),
        row({
          journal: [
            { account: "6010 급여", debit: 200, credit: 0 },
            { account: "1010 보통예금", debit: 0, credit: 200 },
          ],
        }),
      ],
      [],
      [],
      "2026-09-30",
    );
    const month = result.months[5];
    expect(month.inflow).toBe(500);
    expect(month.outflow).toBe(200);
    expect(result.cash).toBe(300);
    expect(result.assets).toBe(result.equity);
  });
  it("빈 저장소를 정상 대사 완료로 오인하지 않도록 전기 건수를 제공한다", () => {
    const result = financeOverviewModel([], [], [], "2026-09-30");
    expect(result.postedCount).toBe(0);
    expect(result.ar.total).toBe(0);
    expect(result.months).toHaveLength(6);
  });
});
