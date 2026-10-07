import type { RecordData } from "../../domain/types";
export const accountNames = [
  "1010 보통예금",
  "1130 매출채권",
  "1310 원재료",
  "1330 제품",
  "2110 매입채무",
  "3010 자본금",
  "4010 제품매출",
  "5010 매출원가",
  "6010 급여",
  "6020 복리후생비",
  "1340 재공품",
  "2120 예수금",
];
export function accountingFixture(key: string, r: RecordData, i: number) {
  r.partner = "";
  if (key === "accounts") {
    const account = accountNames[i % accountNames.length];
    r.title = account.slice(5);
    r.values.code = account.slice(0, 4);
    r.values.category =
      account[0] === "1"
        ? "자산"
        : account[0] === "2"
          ? "부채"
          : account[0] === "3"
            ? "자본"
            : account[0] === "4"
              ? "수익"
              : "비용";
    r.values.normal = ["자산", "비용"].includes(String(r.values.category))
      ? "차변"
      : "대변";
    r.status = "사용중";
    r.amount = 0;
  }
  if (key === "journals") {
    const n = 15000000 + (i % 6) * 1250000;
    const pairs = [
      ["1010 보통예금", "3010 자본금", "자본금 납입"],
      ["1310 원재료", "2110 매입채무", "정기 원자재 매입"],
      ["1130 매출채권", "4010 제품매출", "한빛전자 제품 매출"],
      ["5010 매출원가", "1330 제품", "제품 출고 원가"],
      ["1010 보통예금", "1130 매출채권", "거래처 매출채권 수금"],
      ["2110 매입채무", "1010 보통예금", "원자재 매입대금 지급"],
      ["6010 급여", "1010 보통예금", "9월 급여 지급"],
      ["1330 제품", "1310 원재료", "제품 생산 대체"],
    ];
    const p = pairs[i % 8];
    r.title = p[2];
    r.journal = [
      { account: p[0], debit: n, credit: 0 },
      { account: p[1], debit: 0, credit: n },
    ];
    r.amount = n;
    r.values.category = [
      "일반전표",
      "매입전표",
      "매출전표",
      "일반전표",
      "입금전표",
      "출금전표",
      "급여전표",
      "일반전표",
    ][i % 8];
    r.status = i === 23 ? "작성중" : "전기완료";
  }
  if (key === "fiscal-periods") {
    r.title = `2026년 ${i + 1}월`;
    r.date = `2026-${String(i + 1).padStart(2, "0")}-01`;
    r.dueDate = `2026-${String(i + 1).padStart(2, "0")}-${[31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][i]}`;
    r.values.journalCount = i === 8 ? 23 : 0;
    r.status = i < 8 ? "마감" : "진행중";
    r.amount = 0;
  }
}
