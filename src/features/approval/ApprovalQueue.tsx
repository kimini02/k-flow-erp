import { useState } from "react";
import { Button } from "@mantine/core";
import type { RecordData } from "../../domain/types";
import { won } from "../../domain/helpers";

// Documents whose current (waiting) step belongs to the signed-in demo user.
export function ApprovalQueue({
  rows,
  actor,
  loading,
  onOpen,
  selectedId,
}: {
  rows: RecordData[];
  actor: string;
  loading?: boolean;
  onOpen: (r: RecordData) => void;
  selectedId?: string | null;
}) {
  const mine = rows
    .filter(
      (r) =>
        ["상신", "결재중"].includes(r.status) &&
        r.approvals.find((s) => s.status === "대기")?.name === actor,
    )
    .sort((a, b) => a.date.localeCompare(b.date));
  const [expanded, setExpanded] = useState(false);
  const visible = expanded ? mine : mine.slice(0, 5);
  return (
    <section
      className="panel approval-queue"
      aria-labelledby="approval-queue-title"
    >
      <div className="approval-queue-heading">
        <h2 id="approval-queue-title">
          내가 처리할 결재 <span>{loading ? "—" : mine.length}</span>
        </h2>
        <span>
          <b>{actor}</b> 님 차례인 문서 · 순차 결재 · 오래 기다린 순
        </span>
      </div>
      {!loading && !mine.length ? (
        <p className="approval-queue-empty">
          지금 처리할 결재가 없습니다. 상단 사용자 선택으로 다른 결재자를 시연할
          수 있습니다.
        </p>
      ) : (
        <ul>
          {visible.map((r) => {
            const step = r.approvals.find((s) => s.status === "대기")!;
            const done = r.approvals.filter((s) => s.status === "승인").length;
            return (
              <li
                key={r.id}
                className={selectedId === r.id ? "selected" : undefined}
              >
                <div>
                  <b>{r.title}</b>
                  <small>
                    {r.id} · {String(r.values.docType || "기안문서")} · 기안{" "}
                    {r.owner}
                  </small>
                </div>
                <span className="money">{r.amount ? won(r.amount) : "—"}</span>
                <span className="approval-step-chip">
                  {step.role} · {done + 1}/{r.approvals.length - 1}단계
                </span>
                <span className="muted">{r.date} 상신</span>
                <Button
                  size="xs"
                  variant="default"
                  aria-label={`${r.id} 검토`}
                  onClick={() => onOpen(r)}
                >
                  검토
                </Button>
              </li>
            );
          })}
        </ul>
      )}
      {mine.length > 5 && (
        <button
          type="button"
          className="approval-queue-more"
          aria-expanded={expanded}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? "접기" : `나머지 ${mine.length - 5}건 더 보기`}
        </button>
      )}
    </section>
  );
}
