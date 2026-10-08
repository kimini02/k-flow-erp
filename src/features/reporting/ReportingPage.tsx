import { FinanceOverview } from "./FinanceOverview";
import { useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { Button, TextInput, Table, Alert, Loader } from "@mantine/core";
import { IconDownload } from "@tabler/icons-react";
import {
  BarChart,
  Bar,
  Cell,
  LabelList,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import { useRecords } from "../../api/hooks";
import { reportingModule } from "./config";
import { PageHeader } from "../../components/PageHeader";
import { sum, shortWon } from "../../domain/helpers";
import { exportCsv } from "../../domain/export";
import { documentUrl } from "../../components/DocumentDetails";
import { StatusBadge } from "../../components/StatusBadge";
import type { RecordData } from "../../domain/types";

// One highlight for the largest group; the rest stay neutral so colour carries meaning.
const HIGHLIGHT = "#2b59c3";
const NEUTRAL = "#b4bcc7";
const num = (r: RecordData, key: string) => Number(r.values[key] ?? 0);
type Screen = "overview" | "purchasing" | "sales" | "inventory" | "production";
const SETUP: Record<
  Screen,
  {
    resource: string;
    group: string;
    groupLabel: string;
    measure: string;
    unit: "원" | "EA";
    list: string;
  }
> = {
  overview: {
    resource: "sales-orders",
    group: "partner",
    groupLabel: "거래처",
    measure: "주문 금액",
    unit: "원",
    list: "/sales/orders",
  },
  purchasing: {
    resource: "purchase-orders",
    group: "partner",
    groupLabel: "거래처",
    measure: "발주 금액",
    unit: "원",
    list: "/purchasing/orders",
  },
  sales: {
    resource: "invoices",
    group: "partner",
    groupLabel: "거래처",
    measure: "매출 금액",
    unit: "원",
    list: "/sales/invoices",
  },
  inventory: {
    resource: "stock",
    group: "warehouse",
    groupLabel: "창고",
    measure: "재고 평가액",
    unit: "원",
    list: "/inventory/stock",
  },
  production: {
    resource: "work-orders",
    group: "line",
    groupLabel: "생산라인",
    measure: "생산 수량",
    unit: "EA",
    list: "/manufacturing/orders",
  },
};

export function ReportingPage() {
  const { screen = "overview" } = useParams();
  return screen === "overview" ? <FinanceOverview /> : <BusinessAnalysis />;
}
function BusinessAnalysis() {
  const { screen: param = "purchasing" } = useParams();
  const screen = (param in SETUP ? param : "purchasing") as Screen;
  const setup = SETUP[screen];
  const [from, setFrom] = useState("2026-09-01");
  const [to, setTo] = useState("2026-09-30");
  const query = useRecords(setup.resource, { from, to, size: 100000 });
  const rows = query.data?.rows ?? [];
  const production = screen === "production";
  const groupOf = (r: RecordData) =>
    setup.group === "partner"
      ? r.partner
      : String(r.values[setup.group] ?? "—");
  const measureOf = (r: RecordData) =>
    production ? num(r, "produced") : r.amount;
  const groups = new Map<string, { value: number; docs: number }>();
  for (const r of rows) {
    const g = groups.get(groupOf(r)) ?? { value: 0, docs: 0 };
    g.value += measureOf(r);
    g.docs += 1;
    groups.set(groupOf(r), g);
  }
  const total = [...groups.values()].reduce((n, g) => n + g.value, 0);
  const data = [...groups.entries()]
    .map(([name, g]) => ({
      name,
      value: g.value,
      docs: g.docs,
      share: total ? (g.value / total) * 100 : 0,
    }))
    .sort((a, b) => b.value - a.value);
  const format = (n: number) =>
    setup.unit === "EA"
      ? `${n.toLocaleString("ko-KR")} EA`
      : `${shortWon(n)}원`;
  const title =
    reportingModule.items.find((i) => i.key === screen)?.label ?? "경영 분석";
  const planned = sum(rows, "qty"),
    produced = sum(rows, "produced");
  const metrics = production
    ? [
        ["작업지시", rows.length, "건"],
        ["계획 수량", planned.toLocaleString("ko-KR"), "EA"],
        ["생산 수량", produced.toLocaleString("ko-KR"), "EA"],
        [
          "계획 달성률",
          planned ? Math.round((produced / planned) * 100) : 0,
          "%",
        ],
      ]
    : [
        ["분석 대상 문서", rows.length, "건"],
        [setup.measure, shortWon(total), "원"],
        ["문서당 평균", shortWon(rows.length ? total / rows.length : 0), "원"],
        [`${setup.groupLabel} 수`, groups.size, "개"],
      ];
  const columns: [string, (r: RecordData) => ReactNode, boolean?][] = production
    ? [
        ["생산 품목", (r) => r.title],
        ["생산라인", (r) => String(r.values.line ?? "—")],
        ["일자", (r) => r.date],
        ["계획", (r) => num(r, "qty").toLocaleString("ko-KR"), true],
        ["생산", (r) => num(r, "produced").toLocaleString("ko-KR"), true],
      ]
    : screen === "inventory"
      ? [
          ["품목", (r) => r.title],
          ["창고", (r) => String(r.values.warehouse ?? "—")],
          ["현재고", (r) => num(r, "qty").toLocaleString("ko-KR"), true],
          ["평가액", (r) => r.amount.toLocaleString("ko-KR"), true],
        ]
      : [
          ["문서명", (r) => r.title],
          ["거래처", (r) => r.partner || "—"],
          ["일자", (r) => r.date],
          ["금액", (r) => r.amount.toLocaleString("ko-KR"), true],
        ];
  return (
    <>
      <PageHeader
        title={title}
        description="업무 문서를 기준으로 실적을 분석합니다. 회계 확정 실적은 재무제표에서 확인합니다."
        actions={
          <Button
            variant="default"
            leftSection={<IconDownload size={15} />}
            onClick={() =>
              exportCsv(
                rows,
                [
                  { key: "id", label: "문서번호" },
                  { key: "title", label: "문서명" },
                  { key: "partner", label: "거래처" },
                  { key: "date", label: "일자" },
                  { key: "amount", label: "금액" },
                  { key: "status", label: "상태" },
                ],
                `${title}.csv`,
              )
            }
          >
            보고서 내보내기
          </Button>
        }
      />
      <div className="report-toolbar">
        <TextInput
          label="시작일"
          type="date"
          value={from}
          onChange={(e) => setFrom(e.currentTarget.value)}
        />
        <TextInput
          label="종료일"
          type="date"
          value={to}
          onChange={(e) => setTo(e.currentTarget.value)}
        />
        <span>
          거래 문서 기준 집계 · 취소 문서 포함 · 회계 확정 실적은 재무제표 참조
        </span>
      </div>
      <dl className="summary-strip" aria-busy={query.isLoading || undefined}>
        {metrics.map(([label, value, unit]) => (
          <div key={String(label)}>
            <dt>{label}</dt>
            <dd>
              {query.isLoading ? "—" : value}
              {!query.isLoading && <small>{unit}</small>}
            </dd>
          </div>
        ))}
      </dl>
      {query.isLoading ? (
        <Loader />
      ) : query.error ? (
        <Alert color="red">
          {query.error.message}
          <Button onClick={() => query.refetch()}>다시 시도</Button>
        </Alert>
      ) : (
        <>
          <section className="panel analysis-panel">
            <div className="panel-heading">
              <div>
                <h2>
                  {setup.groupLabel}별 {setup.measure}
                </h2>
                <p>
                  {from} ~ {to} · 큰 순서 · 막대 끝은 {setup.measure}와 비중
                </p>
              </div>
            </div>
            <div
              className="analysis-chart"
              style={{ height: Math.max(180, data.length * 40 + 40) }}
            >
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={data}
                  layout="vertical"
                  margin={{ top: 4, right: 150, left: 8, bottom: 4 }}
                >
                  <CartesianGrid horizontal={false} stroke="#eceef1" />
                  <XAxis
                    type="number"
                    tickFormatter={(n) =>
                      setup.unit === "EA"
                        ? Number(n).toLocaleString("ko-KR")
                        : shortWon(Number(n))
                    }
                    tick={{ fontSize: 12, fill: "#6b7280" }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    type="category"
                    dataKey="name"
                    width={120}
                    tick={{ fontSize: 13, fill: "#1f2328" }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <Tooltip
                    cursor={{ fill: "#f7f8fa" }}
                    formatter={(v) => [format(Number(v)), setup.measure]}
                  />
                  <Bar
                    dataKey="value"
                    radius={[0, 3, 3, 0]}
                    maxBarSize={22}
                    isAnimationActive={false}
                  >
                    {data.map((d, i) => (
                      <Cell key={d.name} fill={i === 0 ? HIGHLIGHT : NEUTRAL} />
                    ))}
                    <LabelList
                      dataKey="value"
                      position="right"
                      content={({ x, y, width, height, index }) => {
                        const d = data[Number(index)];
                        if (!d) return null;
                        return (
                          <text
                            x={Number(x) + Number(width) + 8}
                            y={Number(y) + Number(height) / 2}
                            dominantBaseline="central"
                            fontSize={12}
                            fill="#4b5563"
                          >
                            {format(d.value)} · {d.share.toFixed(1)}%
                          </text>
                        );
                      }}
                    />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </section>
          <section className="panel analysis-panel">
            <div className="panel-heading">
              <div>
                <h2>
                  분석 근거 문서{" "}
                  <span className="count-pill">{rows.length}</span>
                </h2>
                <p>문서번호를 누르면 해당 업무 화면에서 문서를 엽니다</p>
              </div>
              <Link className="text-link" to={setup.list}>
                전체 업무 목록 ↗
              </Link>
            </div>
            <div className="data-table">
              <div className="table-scroll analysis-table">
                <Table miw={720} highlightOnHover={false}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th style={{ width: 150 }}>문서번호</Table.Th>
                      {columns.map(([label, , end]) => (
                        <Table.Th
                          key={label}
                          data-align={end ? "end" : undefined}
                        >
                          {label}
                        </Table.Th>
                      ))}
                      <Table.Th style={{ width: 116 }}>상태</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {rows.map((r) => (
                      <Table.Tr key={r.id}>
                        <Table.Td>
                          <Link
                            className="doc-link"
                            to={documentUrl(setup.resource, r.id)}
                          >
                            {r.id}
                          </Link>
                        </Table.Td>
                        {columns.map(([label, value, end]) => (
                          <Table.Td
                            key={label}
                            data-align={end ? "end" : undefined}
                          >
                            {value(r)}
                          </Table.Td>
                        ))}
                        <Table.Td>
                          <StatusBadge status={r.status} />
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
                {!rows.length && (
                  <div className="empty-state">
                    해당 기간에 데이터가 없습니다.
                  </div>
                )}
              </div>
            </div>
          </section>
        </>
      )}
    </>
  );
}
