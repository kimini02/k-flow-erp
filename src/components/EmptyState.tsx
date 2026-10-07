import { IconInbox } from "@tabler/icons-react";
import { Button } from "@mantine/core";
export function EmptyState({
  message = "조회된 데이터가 없습니다",
  onReset,
}: {
  message?: string;
  onReset?: () => void;
}) {
  return (
    <div className="empty-state">
      <IconInbox size={35} stroke={1.3} />
      <strong>{message}</strong>
      <span>검색어와 필터 조건을 확인해 주세요.</span>
      {onReset && (
        <Button variant="light" onClick={onReset}>
          필터 초기화
        </Button>
      )}
    </div>
  );
}
