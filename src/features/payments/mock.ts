import type { RecordData } from "../../domain/types";
export function paymentsFixture(key: string, r: RecordData, i: number) {
  if (["payables", "receivables"].includes(key)) {
    r.values.balance =
      i % 4 === 2 ? 0 : Math.round(r.amount * (i % 4 === 1 ? 0.5 : 1));
    r.values.aging = (i % 5) * 15;
    r.dueDate =
      i % 4 === 3
        ? "2026-09-15"
        : `2026-10-${String(1 + (i % 12)).padStart(2, "0")}`;
    r.status =
      Number(r.values.balance) === 0
        ? key === "payables"
          ? "지급완료"
          : "수금완료"
        : i % 4 === 3
          ? "연체"
          : i % 4 === 1
            ? key === "payables"
              ? "부분지급"
              : "부분수금"
            : key === "payables"
              ? "미지급"
              : "미수금";
  }
  if (["payments-out", "payments-in"].includes(key)) {
    r.title = `${r.partner} ${key === "payments-out" ? "매입대금 지급" : "매출대금 수금"}`;
    r.values.bank = "기업은행 123-456789-01";
    r.status = key === "payments-out" ? "지급완료" : "수금완료";
  }
}
