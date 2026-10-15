import type { RecordData } from "../../domain/types";
import { DEPARTMENTS } from "../../domain/types";
const names = [
  "김민희",
  "정수현",
  "박지훈",
  "이서연",
  "김도윤",
  "최정우",
  "박수진",
  "장유진",
  "오세훈",
  "임지은",
  "한소영",
  "윤태호",
];
export function hrFixture(key: string, r: RecordData, i: number) {
  r.partner = "";
  if (key === "employees") {
    r.title = names[i % 12];
    r.values.position = ["대리", "과장", "사원", "차장"][i % 4];
    r.values.email = `staff${String(i + 1).padStart(3, "0")}@hangyeol.example`;
    r.values.phone = `010-0000-${String(1000 + i)}`;
    r.date = `202${i % 5}-03-02`;
    r.status = i === 7 ? "휴직" : "재직";
    r.amount = 0;
  }
  if (key === "departments") {
    r.title = DEPARTMENTS[i % 8];
    r.values.manager = ["김팀장", "정영호", "박성진", "이도현"][i % 4];
    r.values.parent = i < 4 ? "경영본부" : "생산본부";
    r.values.headcount = 20 + i * 3;
    r.values.budget = 15000000 + i * 5000000;
    r.amount = 0;
  }
  if (key === "positions") {
    r.title = ["사원", "대리", "과장", "차장", "부장", "이사"][i % 6];
    r.values.level = i + 1;
    r.values.baseSalary = 2900000 + i * 800000;
    r.values.headcount = 60 - i * 8;
    r.amount = 0;
  }
  if (key === "attendance") {
    r.title = names[i % 12];
    r.values.checkIn = i % 7 === 0 ? "09:12" : "08:52";
    r.values.checkOut = "18:05";
    r.values.hours = 8;
    r.date = "2026-09-29";
    r.status = i % 7 === 0 ? "지각" : "정시";
    r.amount = 0;
  }
  if (key === "leave") {
    r.title = `${names[i % 12]} 연차 신청`;
    r.values.days = 1 + (i % 2);
    r.values.remaining = 15 - Number(r.values.days);
    r.date = `2026-09-${String(10 + (i % 18)).padStart(2, "0")}`;
    r.dueDate = r.date;
    r.values.reason = "개인 용무";
    r.status = i % 3 === 0 ? "작성중" : i % 3 === 1 ? "승인대기" : "승인완료";
    r.amount = 0;
  }
  if (key === "payroll") {
    r.title = `2026년 9월 ${r.department} 급여`;
    r.values.month = "2026-09";
    r.values.gross = 42000000 + i * 550000;
    r.values.tax = Math.round(Number(r.values.gross) * 0.055);
    r.values.insurance = Math.round(Number(r.values.gross) * 0.095);
    r.values.deduction = Number(r.values.tax) + Number(r.values.insurance);
    r.values.net = Number(r.values.gross) - Number(r.values.deduction);
    r.amount = Number(r.values.net);
    r.date = "2026-09-25";
  }
}
