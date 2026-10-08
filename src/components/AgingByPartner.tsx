import { Table } from "@mantine/core";
import type { RecordData } from "../domain/types";
import { agingSummary } from "../features/reporting/financeOverviewModel";
import { EmptyState } from "./EmptyState";

const amount = (n: number) => (n ? n.toLocaleString("ko-KR") : "—");
type PartnerAging = {
  partner: string;
  total: number;
  buckets: number[];
  overdueDocs: number;
  earliestDue: string;
};

// Same buckets as the finance overview: current balance grouped by registered overdue days.
export function AgingByPartner({
  rows,
  kind,
  onPartner,
  onReset,
}: {
  rows: RecordData[];
  kind: "payables" | "receivables";
  onPartner: (partner: string) => void;
  onReset?: () => void;
}) {
  const open = rows.filter((r) => Number(r.values.balance ?? 0) > 0);
  const partners = [...new Set(open.map((r) => r.partner))];
  const data: PartnerAging[] = partners
    .map((partner) => {
      const docs = open.filter((r) => r.partner === partner);
      const summary = agingSummary(docs);
      return {
        partner,
        total: summary.total,
        buckets: summary.groups.map((g) => g.value),
        overdueDocs: docs.filter((r) => Number(r.values.aging ?? 0) > 0).length,
        earliestDue: docs.map((r) => r.dueDate).sort()[0] ?? "—",
      };
    })
    .sort(
      (a, b) =>
        b.total - b.buckets[0] - (a.total - a.buckets[0]) || b.total - a.total,
    );
  const labels = agingSummary([]).groups.map((g) => g.name);
  const totals = labels.map((_, i) =>
    data.reduce((n, d) => n + d.buckets[i], 0),
  );
  const grand = totals.reduce((n, v) => n + v, 0);
  const settled = new Set(rows.map((r) => r.partner)).size - partners.length;
  if (!open.length)
    return <EmptyState message="미결제 잔액이 없습니다" onReset={onReset} />;
  return (
    <div className="data-table">
      <div className="table-scroll">
        <Table miw={900} highlightOnHover={false}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th style={{ width: 170 }}>거래처</Table.Th>
              <Table.Th data-align="end">
                {kind === "payables" ? "미지급 잔액" : "미수금 잔액"}
              </Table.Th>
              {labels.map((label) => (
                <Table.Th key={label} data-align="end">
                  {label}
                </Table.Th>
              ))}
              <Table.Th data-align="end">연체 문서</Table.Th>
              <Table.Th style={{ width: 120 }}>가장 이른 기한</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {data.map((d) => (
              <Table.Tr
                key={d.partner}
                className="clickable-row"
                onClick={() => onPartner(d.partner)}
              >
                <Table.Td>
                  <button
                    type="button"
                    className="doc-link"
                    aria-label={`${d.partner} 문서 보기`}
                    onClick={(event) => {
                      event.stopPropagation();
                      onPartner(d.partner);
                    }}
                  >
                    {d.partner}
                  </button>
                </Table.Td>
                <Table.Td data-align="end">
                  <b>{amount(d.total)}</b>
                </Table.Td>
                {d.buckets.map((value, i) => (
                  <Table.Td
                    key={labels[i]}
                    data-align="end"
                    data-aging={
                      value && i ? (i === 1 ? "warn" : "late") : undefined
                    }
                    className={value ? undefined : "muted"}
                  >
                    {amount(value)}
                  </Table.Td>
                ))}
                <Table.Td
                  data-align="end"
                  className={d.overdueDocs ? undefined : "muted"}
                >
                  {d.overdueDocs ? `${d.overdueDocs}건` : "—"}
                </Table.Td>
                <Table.Td>{d.earliestDue}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
          <Table.Tfoot>
            <Table.Tr className="total-row">
              <Table.Td>
                합계 <small>거래처 {data.length}곳</small>
              </Table.Td>
              <Table.Td data-align="end">{amount(grand)}</Table.Td>
              {totals.map((value, i) => (
                <Table.Td key={labels[i]} data-align="end">
                  {amount(value)}
                </Table.Td>
              ))}
              <Table.Td data-align="end">
                {data.reduce((n, d) => n + d.overdueDocs, 0)}건
              </Table.Td>
              <Table.Td />
            </Table.Tr>
          </Table.Tfoot>
        </Table>
      </div>
      <div className="table-footer">
        <span>
          잔액이 있는 거래처 <b>{data.length}</b>곳
          {settled > 0 && (
            <span className="muted"> · 잔액 없는 거래처 {settled}곳 제외</span>
          )}
        </span>
        <span className="muted">
          단위 원 · 현재 잔액을 등록된 연체일로 구분 · 거래처를 누르면 문서
          목록으로 이동
        </span>
      </div>
    </div>
  );
}
