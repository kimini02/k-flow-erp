import { Progress } from "@mantine/core";
import type { RecordData } from "../../domain/types";
import { StatusBadge } from "../../components/StatusBadge";
export function ProductionOverview({
  rows,
  onOpen,
}: {
  rows: RecordData[];
  onOpen: (row: RecordData) => void;
}) {
  const lines = ["A라인 · 조립", "B라인 · 가공", "C라인 · 검사"];
  return (
    <div className="production-board">
      {lines.map((line) => {
        const orders = rows.filter((r) => r.values.line === line);
        const planned = orders.reduce(
          (n, r) => n + Number(r.values.qty ?? 0),
          0,
        );
        const produced = orders.reduce(
          (n, r) => n + Number(r.values.produced ?? 0),
          0,
        );
        return (
          <section key={line} className="panel production-line">
            <div className="panel-heading">
              <h2>{line}</h2>
              <span>{orders.length}건</span>
            </div>
            <div className="production-progress">
              <strong>
                {planned ? Math.round((produced / planned) * 100) : 0}
                <small>%</small>
              </strong>
              <span>
                {produced.toLocaleString()} / {planned.toLocaleString()} EA
              </span>
              <Progress
                value={planned ? (produced / planned) * 100 : 0}
                size={6}
              />
            </div>
            <div className="production-jobs">
              {orders
                .filter((r) => r.status !== "취소")
                .slice(0, 3)
                .map((r) => (
                  <button key={r.id} onClick={() => onOpen(r)}>
                    <span>
                      {r.id}
                      <b>{r.title}</b>
                    </span>
                    <StatusBadge status={r.status} />
                  </button>
                ))}
            </div>
          </section>
        );
      })}
    </div>
  );
}
