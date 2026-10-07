import { IconCheck, IconChevronRight } from "@tabler/icons-react";
export function DocumentFlow({
  steps,
  current = 0,
}: {
  steps: string[];
  current?: number;
}) {
  return (
    <div className="document-flow">
      {steps.map((s, i) => (
        <div
          className={i < current ? "done" : i === current ? "current" : ""}
          key={s}
        >
          <span>{i < current ? <IconCheck size={12} /> : i + 1}</span>
          <b>{s}</b>
          {i < steps.length - 1 && <IconChevronRight size={12} />}
        </div>
      ))}
    </div>
  );
}
