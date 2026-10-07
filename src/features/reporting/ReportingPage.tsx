import { FinanceOverview } from "./FinanceOverview";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  Button,
  TextInput,
  Table,
  Alert,
  Loader,
  Progress,
} from "@mantine/core";
import { IconDownload, IconArrowUpRight } from "@tabler/icons-react";
import {
  BarChart,
  Bar,
  Cell,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import { useRecords } from "../../api/hooks";
import { reportingModule } from "./config";
import { PageHeader } from "../../components/PageHeader";
import { KpiCard } from "../../components/KpiCard";
import { sum, shortWon, won } from "../../domain/helpers";
import { exportCsv } from "../../domain/export";
import { documentUrl } from "../../components/DocumentDetails";
import { StatusBadge } from "../../components/StatusBadge";
import { PARTNERS, WAREHOUSES } from "../../domain/types";

const REPORT_COLORS = [
  "#525252",
  "#737373",
  "#969696",
  "#404040",
  "#262626",
  "#616161",
  "#a3a3a3",
  "#858585",
];
function categoryColor(name: string) {
  // Category identity keeps its color when the date range or ranking changes.
  const categories = [
    PARTNERS,
    WAREHOUSES,
    ["A라인 · 조립", "B라인 · 가공", "C라인 · 검사"],
  ];
  const known = categories.find((names) => names.includes(name));
  const index = known
    ? known.indexOf(name)
    : Array.from(name).reduce(
        (hash, char) => (hash * 31 + char.charCodeAt(0)) >>> 0,
        0,
      );
  return REPORT_COLORS[index % REPORT_COLORS.length];
}

export function ReportingPage() {
  const { screen = "overview" } = useParams();
  return screen === "overview" ? <FinanceOverview /> : <BusinessAnalysis />;
}
function BusinessAnalysis() {
  const { screen = "overview" } = useParams();
  const [from, setFrom] = useState("2026-09-01");
  const [to, setTo] = useState("2026-09-30");
  const resource =
    (
      {
        overview: "sales-orders",
        purchasing: "purchase-orders",
        sales: "invoices",
        inventory: "stock",
        production: "work-orders",
      } as Record<string, string>
    )[screen] ?? "sales-orders";
  const query = useRecords(resource, { from, to, size: 100000 });
  const rows = query.data?.rows ?? [];
  const groups = new Map<string, number>();
  rows.forEach((r) => {
    const label =
      screen === "inventory"
        ? String(r.values.warehouse)
        : screen === "production"
          ? String(r.values.line)
          : r.partner;
    groups.set(
      label,
      (groups.get(label) ?? 0) +
        (screen === "production" ? Number(r.values.produced ?? 0) : r.amount),
    );
  });
  const data = [...groups.entries()]
    .map(([name, value]) => ({
      name,
      color: categoryColor(name),
      value: screen === "production" ? value : Math.round(value / 10000),
    }))
    .sort((a, b) => b.value - a.value);
  const title =
    reportingModule.items.find((i) => i.key === screen)?.label ?? "경영 분석";
  const total = sum(rows);
  return (
    <>
      <PageHeader
        title={title}
        description="업무 문서를 기준으로 실적과 주요 지표를 분석합니다. 기간 변경과 상세 문서 조회를 지원합니다."
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
        <span>거래 문서 기준 집계 · 회계 확정 실적은 회계 보고서 참조</span>
      </div>
      <div className="kpi-grid">
        <KpiCard
          label="분석 대상 문서"
          value={
            <>
              {rows.length}
              <small>건</small>
            </>
          }
          detail="선택한 조회기간 기준"
        />
        <KpiCard
          label="문서 금액 합계"
          value={
            <>
              {shortWon(total)}
              <small>원</small>
            </>
          }
          detail="취소 문서 포함 · 문서 기준"
        />
        <KpiCard
          label="평균 문서 금액"
          value={
            <>
              {shortWon(rows.length ? total / rows.length : 0)}
              <small>원</small>
            </>
          }
          detail="총금액 ÷ 문서수"
        />
        <KpiCard
          label={
            screen === "inventory"
              ? "창고 수"
              : screen === "production"
                ? "생산라인 수"
                : "거래처 수"
          }
          value={
            <>
              {groups.size}
              <small>개</small>
            </>
          }
          detail="조회 결과의 고유 그룹"
        />
      </div>
      {query.isLoading ? (
        <Loader />
      ) : query.error ? (
        <Alert color="red">
          {query.error.message}
          <Button onClick={() => query.refetch()}>다시 시도</Button>
        </Alert>
      ) : (
        <>
          <div className="dashboard-top-grid">
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>
                    {screen === "production"
                      ? "라인별 생산량"
                      : screen === "inventory"
                        ? "창고별 재고 평가액"
                        : "거래처별 거래 규모"}
                  </h2>
                  <p>단위: {screen === "production" ? "EA" : "만원"}</p>
                </div>
              </div>
              <div style={{ height: 260, padding: "0 25px 20px" }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={data.slice(0, 8)}
                    margin={{ left: 0, right: 10, bottom: 8 }}
                  >
                    <CartesianGrid vertical={false} stroke="#efefef" />
                    <XAxis
                      dataKey="name"
                      tick={{ fontSize: 10, fill: "#929292" }}
                      axisLine={false}
                      tickLine={false}
                    />
                    <YAxis
                      tick={{ fontSize: 10, fill: "#929292" }}
                      axisLine={false}
                      tickLine={false}
                    />
                    <Tooltip />
                    <Bar
                      dataKey="value"
                      name={screen === "production" ? "생산량" : "금액 (만원)"}
                      fill={REPORT_COLORS[0]}
                      radius={[4, 4, 0, 0]}
                      maxBarSize={40}
                      isAnimationActive={false}
                    >
                      {data.slice(0, 8).map((item) => (
                        <Cell key={item.name} fill={item.color} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </section>
            <section className="panel">
              <div className="panel-heading">
                <h2>상위 비중</h2>
              </div>
              <div className="ranking">
                {data.slice(0, 5).map((d, i) => (
                  <div key={d.name}>
                    <span>{String(i + 1).padStart(2, "0")}</span>
                    <div>
                      <b className="report-category-label">
                        <i
                          style={{ backgroundColor: d.color }}
                          aria-hidden="true"
                        />
                        {d.name}
                      </b>
                      <Progress
                        value={
                          (d.value /
                            Math.max(
                              1,
                              data.reduce((n, x) => n + x.value, 0),
                            )) *
                          100
                        }
                        size={5}
                        color={d.color}
                      />
                    </div>
                    <strong>
                      {(
                        (d.value /
                          Math.max(
                            1,
                            data.reduce((n, x) => n + x.value, 0),
                          )) *
                        100
                      ).toFixed(1)}
                      %
                    </strong>
                  </div>
                ))}
              </div>
            </section>
          </div>
          <section className="panel">
            <div className="panel-heading">
              <h2>분석 근거 문서</h2>
              <Link
                className="text-link"
                to={
                  screen === "production"
                    ? "/manufacturing/orders"
                    : screen === "inventory"
                      ? "/inventory/stock"
                      : screen === "purchasing"
                        ? "/purchasing/orders"
                        : "/sales/orders"
                }
              >
                전체 업무 목록 ↗
              </Link>
            </div>
            <div className="table-scroll">
              <Table miw={650}>
                <Table.Thead>
                  <Table.Tr>
                    {[
                      "문서번호",
                      "문서명",
                      "거래처 / 담당부서",
                      "일자",
                      "금액",
                      "상태",
                      "",
                    ].map((s, i) => (
                      <Table.Th key={i}>{s}</Table.Th>
                    ))}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((r) => (
                    <Table.Tr key={r.id}>
                      <Table.Td>
                        <Link
                          className="text-link"
                          to={documentUrl(resource, r.id)}
                        >
                          {r.id}
                        </Link>
                      </Table.Td>
                      <Table.Td>{r.title}</Table.Td>
                      <Table.Td>{r.partner || r.department}</Table.Td>
                      <Table.Td>{r.date}</Table.Td>
                      <Table.Td>{won(r.amount)}</Table.Td>
                      <Table.Td>
                        <StatusBadge status={r.status} />
                      </Table.Td>
                      <Table.Td>
                        <Link
                          to={documentUrl(resource, r.id)}
                          aria-label={`${r.id} 상세`}
                        >
                          <IconArrowUpRight size={14} />
                        </Link>
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
          </section>
        </>
      )}
    </>
  );
}
