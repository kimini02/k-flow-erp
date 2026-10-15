// Finished states stay quiet; states that need someone's action stand out.
const success = [
  "승인완료",
  "발주완료",
  "주문전환",
  "입고완료",
  "지급완료",
  "수금완료",
  "생산완료",
  "전기완료",
  "정상",
  "재직",
  "사용중",
  "활성",
  "출고완료",
  "완료",
  "정시",
];
const danger = ["반려", "부족", "연체", "지각"];
const waiting = [
  "결재중",
  "상신",
  "승인대기",
  "부분입고",
  "부분출고",
  "미지급",
  "부분지급",
  "부분수금",
  "검수중",
  "미수금",
  "대기",
];
const info = ["확정", "진행중", "생산중"];
export type StatusTone = "success" | "danger" | "waiting" | "info" | "neutral";
export function statusTone(status: string): StatusTone {
  if (danger.includes(status)) return "danger";
  if (waiting.includes(status)) return "waiting";
  if (info.includes(status)) return "info";
  if (success.includes(status)) return "success";
  return "neutral";
}
export function StatusBadge({ status }: { status: string }) {
  return (
    <span className="status-badge" data-tone={statusTone(status)}>
      <i aria-hidden="true" />
      {status}
    </span>
  );
}
