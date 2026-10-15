import type { ApprovalStep } from "../domain/types";
import { IconCheck, IconClock, IconPencil, IconX } from "@tabler/icons-react";
export function ApprovalTimeline({ steps }: { steps: ApprovalStep[] }) {
  return (
    <div className="approval-timeline">
      {steps.map((s, i) => (
        <div
          className={`approval-step ${s.status === "승인" ? "done" : s.status === "대기" ? "current" : s.status === "반려" ? "rejected" : ""}`}
          key={i}
        >
          <span className="approval-node">
            {s.status === "승인" ? (
              <IconCheck size={15} />
            ) : s.status === "대기" ? (
              <IconClock size={15} />
            ) : s.status === "기안" ? (
              <IconPencil size={14} />
            ) : s.status === "반려" ? (
              <IconX size={14} />
            ) : (
              i
            )}
          </span>
          <div>
            <b>{s.name}</b>
            <small>{s.role}</small>
          </div>
          <span className="approval-step-status">{s.status}</span>
        </div>
      ))}
    </div>
  );
}
