import { Progress } from "@mantine/core";
import type { RecordData } from "../../domain/types";
import { StatusBadge } from "../../components/StatusBadge";
const LINES = ["A라인 · 조립", "B라인 · 가공", "C라인 · 검사"];
// Running work first so the board answers "what is on each line now".
const statusOrder = ["생산중", "확정", "작성중", "생산완료", "취소"];
export function ProductionOverview({
  rows,
  loading,
  onOpen,
}: {
  rows: RecordData[];
  loading?: boolean;
  onOpen: (row: RecordData) => void;
}) {
  return (
    <div className="production-board">
      {LINES.map((line) => {
        const orders = rows.filter((r) => r.values.line === line);
        const planned = orders.reduce(
          (n, r) => n + Number(r.values.qty ?? 0),
          0,
        );
        const produced = orders.reduce(
          (n, r) => n + Number(r.values.produced ?? 0),
          0,
        );
        const rate = planned ? Math.round((produced / planned) * 100) : 0;
        const running = orders.filter((r) => r.status === "생산중").length;
        return (
          <section key={line} className="panel production-line">
            <div className="panel-heading">
              <h2>{line}</h2>
              <span>
                작업지시 {loading ? "—" : orders.length}건 · 생산중{" "}
                {loading ? "—" : running}건
              </span>
            </div>
            <div className="production-progress">
              <strong>
                {loading ? "—" : rate}
                <small>%</small>
              </strong>
              <span>
                {loading
                  ? "불러오는 중"
                  : `생산 ${produced.toLocaleString("ko-KR")} / 계획 ${planned.toLocaleString("ko-KR")} EA`}
              </span>
              <Progress value={loading ? 0 : rate} size={6} />
            </div>
            <div className="production-jobs">
              {[...orders]
                .filter((r) => r.status !== "취소")
                .sort(
                  (a, b) =>
                    statusOrder.indexOf(a.status) -
                    statusOrder.indexOf(b.status),
                )
                .slice(0, 3)
                .map((r) => (
                  <button
                    key={r.id}
                    type="button"
                    aria-label={`${r.id} 작업지시 보기`}
                    onClick={() => onOpen(r)}
                  >
                    <span>
                      <b>{r.title}</b>
                      <small>
                        {r.id} · 계획{" "}
                        {Number(r.values.qty ?? 0).toLocaleString("ko-KR")} EA
                      </small>
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
