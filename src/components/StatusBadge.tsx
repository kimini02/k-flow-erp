import { Badge } from "@mantine/core";
import { IconCircleCheck, IconCircleX, IconClock, IconCircleDashed } from "@tabler/icons-react";
const positive = [
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
const negative = ["반려", "부족", "연체", "취소", "역분개완료", "지각"];
const waiting = [
  "결재중",
  "상신",
  "승인대기",
  "부분입고",
  "부분출고",
  "진행중",
  "생산중",
  "미지급",
  "부분지급",
  "부분수금",
  "검수중",
  "미수금",
  "대기",
  "휴직",
];
export function StatusBadge({ status }: { status: string }) {
  const tone = positive.includes(status)
    ? "success"
    : negative.includes(status)
      ? "danger"
      : waiting.includes(status)
        ? "waiting"
        : status === "확정"
          ? "confirmed"
          : "neutral";
  const Icon = tone === "success" || tone === "confirmed"
    ? IconCircleCheck
    : tone === "danger"
      ? IconCircleX
      : tone === "waiting"
        ? IconClock
        : IconCircleDashed;
  return (
    <Badge
      className="status-badge"
      data-tone={tone}
      variant="light"
      radius={5}
      fw={600}
      size="md"
      leftSection={<Icon size={12} stroke={1.8} aria-hidden="true" />}
    >
      {status}
    </Badge>
  );
}
