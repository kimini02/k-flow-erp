import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Loader, Table, TextInput } from "@mantine/core";
import {
  IconUsers,
  IconFileInvoice,
  IconChartPie,
  IconBuildingBank,
  IconScale,
  IconCash,
  IconDownload,
  IconArrowUpRight,
} from "@tabler/icons-react";
import {
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  Tooltip,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  LineChart,
  Line,
  Legend,
} from "recharts";
import { api } from "../../api/client";
import { won, shortWon } from "../../domain/helpers";
import { exportCsv } from "../../domain/export";
import type { RecordData } from "../../domain/types";
import { PageHeader } from "../../components/PageHeader";
import { StatusBadge } from "../../components/StatusBadge";
import { financeOverviewModel } from "./financeOverviewModel";

// Aging is ordinal severity: not due stays neutral, overdue warms towards red.
const AGING_COLORS = ["#b4bcc7", "#f59e0b", "#ea580c", "#dc2626", "#991b1b"];
const ACCENT = "#2b59c3";
const NEUTRAL = "#b4bcc7";
const profitColor = (name: string, value: number) =>
  name === "매출액"
    ? ACCENT
    : name === "당기순이익"
      ? value < 0
        ? "#b91c1c"
        : "#15803d"
      : NEUTRAL;
const tick = { fontSize: 12, fill: "#6b7280" };
function Card({
  title,
  note,
  children,
}: {
  title: string;
  note: string;
  children: ReactNode;
}) {
  return (
    <section className="fo-card">
      <header>
        <h2>{title}</h2>
        <p>{note}</p>
      </header>
      {children}
    </section>
  );
}
function AgingDonut({
  groups,
  total,
}: {
  groups: { name: string; value: number }[];
  total: number;
}) {
  return (
    <>
      <div className="fo-donut">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={
                total > 0 ? groups : [{ name: "미결제 잔액 없음", value: 1 }]
              }
              dataKey="value"
              nameKey="name"
              innerRadius={58}
              outerRadius={78}
              paddingAngle={total > 0 ? 2 : 0}
              isAnimationActive={false}
            >
              {(total > 0 ? groups : [{ name: "empty" }]).map((g, i) => (
                <Cell
                  key={g.name}
                  fill={total > 0 ? AGING_COLORS[i] : "#eceef1"}
                />
              ))}
            </Pie>
            {total > 0 && <Tooltip formatter={(v) => won(Number(v))} />}
          </PieChart>
        </ResponsiveContainer>
        <div className="fo-donut-center">
          <small>미결제 잔액</small>
          <strong>{shortWon(total)}원</strong>
        </div>
      </div>
      <ul className="fo-legend">
        {groups.map((g, i) => (
          <li key={g.name}>
            <i style={{ background: AGING_COLORS[i] }} />
            <span>{g.name}</span>
            <b>{won(g.value)}</b>
          </li>
        ))}
      </ul>
    </>
  );
}
export function FinanceOverview() {
  const [asOf, setAsOf] = useState("2026-09-30");
  const invalid = !asOf || asOf < "2026-01-01";
  const query = useQuery({
    queryKey: ["erp", "finance-overview", asOf],
    enabled: !invalid,
    queryFn: async () => {
      const [journals, ar, ap] = await Promise.all(
        ["journals", "receivables", "payables"].map((r) =>
          api.list(r, { size: 100000 }),
        ),
      );
      return financeOverviewModel(journals.rows, ar.rows, ap.rows, asOf);
    },
  });
  const data = query.data;
  const icons = [
    IconUsers,
    IconFileInvoice,
    IconChartPie,
    IconBuildingBank,
    IconScale,
    IconCash,
  ];
  const metrics = data
    ? [
        {
          name: "매출채권",
          value: data.ar.total,
          note: "현재 미수 잔액",
          to: "/sales/receivables",
        },
        {
          name: "매입채무",
          value: data.ap.total,
          note: "현재 미지급 잔액",
          to: "/purchasing/payables",
        },
        {
          name: "당기순이익",
          value: data.income,
          note: "전표 기준 · 데모 계정 분류",
          to: "/accounting/income",
        },
        {
          name: "자산총계",
          value: data.assets,
          note: "2026년 기초부터 전표 누계",
          to: "/accounting/balance-sheet",
        },
        {
          name: "자본총계",
          value: data.equity,
          note: "당기손익 포함",
          to: "/accounting/balance-sheet",
        },
        {
          name: "보통예금 잔액",
          value: data.cash,
          note: "1010 계정 · 은행 연동 아님",
          to: "/accounting/ledger?account=1010%20%EB%B3%B4%ED%86%B5%EC%98%88%EA%B8%88",
        },
      ]
    : [];
  function download() {
    if (!data) return;
    const values = [
      ...metrics.map((m) => ({ label: m.name, value: m.value, basis: m.note })),
      ...data.ar.groups.map((g) => ({
        label: `매출채권 ${g.name}`,
        value: g.value,
        basis: "현재 잔액 · 등록 연체일",
      })),
      ...data.ap.groups.map((g) => ({
        label: `매입채무 ${g.name}`,
        value: g.value,
        basis: "현재 잔액 · 등록 연체일",
      })),
      ...data.months.flatMap((m) => [
        { label: `${m.month} 매출액`, value: m.revenue, basis: "전기된 전표" },
        {
          label: `${m.month} 매출총이익`,
          value: m.grossProfit,
          basis: "전기된 전표",
        },
        {
          label: `${m.month} 예금 입금`,
          value: m.inflow,
          basis: "1010 계정 차변",
        },
        {
          label: `${m.month} 예금 출금`,
          value: m.outflow,
          basis: "1010 계정 대변",
        },
      ]),
      {
        label: "차변 합계",
        value: data.debit,
        basis: "2026년 기초부터 기준일까지",
      },
      {
        label: "대변 합계",
        value: data.credit,
        basis: "2026년 기초부터 기준일까지",
      },
    ];
    exportCsv(
      values.map(
        (v, i) => ({ id: String(i), values: v }) as unknown as RecordData,
      ),
      [
        { key: "label", label: "지표" },
        { key: "value", label: "금액(원)" },
        { key: "basis", label: "집계 기준" },
      ],
      `재무개요-${asOf}.csv`,
    );
  }
  return (
    <>
      <PageHeader
        title="경영 분석"
        description="채권·채무부터 손익과 자금 흐름까지, 회사의 재무 현황을 한눈에 확인합니다."
        actions={
          <Button
            variant="default"
            leftSection={<IconDownload size={15} />}
            disabled={!data || invalid || query.isLoading || !!query.error}
            onClick={download}
          >
            보고서 내보내기
          </Button>
        }
      />
      <div className="fo-navigation">
        <nav aria-label="재무 보고서">
          <span aria-current="page">재무 개요</span>
          <Link to="/accounting/trial-balance">
            시산표 <IconArrowUpRight size={12} />
          </Link>
          <Link to="/accounting/income">
            손익계산서 <IconArrowUpRight size={12} />
          </Link>
          <Link to="/accounting/balance-sheet">
            재무상태표 <IconArrowUpRight size={12} />
          </Link>
        </nav>
        <TextInput
          label="재무 기준일"
          aria-label="재무 기준일"
          type="date"
          min="2026-01-01"
          value={asOf}
          onChange={(e) => setAsOf(e.currentTarget.value)}
        />
      </div>
      <div className="fo-context">
        <p>
          재무지표: 2026.01.01 ~ {asOf || "기준일 선택"} · 채권/채무: 현재 잔액
          및 등록 연체일 기준
        </p>
        <span>
          <i />
          MOCK DATA
        </span>
      </div>
      {invalid ? (
        <Alert color="orange">
          2026년 1월 1일 이후의 재무 기준일을 선택해 주세요.
        </Alert>
      ) : query.isLoading ? (
        <div className="fo-loading">
          <Loader />
          <span>재무 현황을 불러오는 중입니다.</span>
        </div>
      ) : query.error ? (
        <Alert color="red" title="재무 현황을 불러오지 못했습니다">
          {query.error.message}
          <Button onClick={() => query.refetch()}>다시 시도</Button>
        </Alert>
      ) : (
        data && (
          <div className="fo-dashboard">
            <div className="fo-metrics">
              {metrics.map((m, i) => {
                const Icon = icons[i];
                return (
                  <Link key={m.name} to={m.to} className="fo-metric">
                    <div>
                      <span className="fo-icon">
                        <Icon size={17} />
                      </span>
                      <IconArrowUpRight size={13} />
                    </div>
                    <p>{m.name}</p>
                    <strong className={m.value < 0 ? "fo-negative" : ""}>
                      {shortWon(m.value)}
                      <small>원</small>
                    </strong>
                    <span>{m.note}</span>
                  </Link>
                );
              })}
            </div>
            <div className="fo-chart-grid">
              <Card
                title="매출채권 연체 현황"
                note="현재 미수 잔액 · 등록된 연체일로 분류"
              >
                <AgingDonut groups={data.ar.groups} total={data.ar.total} />
              </Card>
              <Card
                title="매입채무 연체 현황"
                note="현재 미지급 잔액 · 등록된 연체일로 분류"
              >
                <AgingDonut groups={data.ap.groups} total={data.ap.total} />
              </Card>
              <Card title="손익 요약" note="선택 기준일까지 전기된 전표 기준">
                <div className="fo-chart">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart
                      data={data.profit}
                      margin={{ left: 0, right: 10, top: 10, bottom: 0 }}
                    >
                      <CartesianGrid vertical={false} stroke="#efefef" />
                      <XAxis
                        dataKey="name"
                        tick={tick}
                        axisLine={false}
                        tickLine={false}
                      />
                      <YAxis
                        width={55}
                        tickFormatter={(n) => shortWon(Number(n))}
                        tick={tick}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip formatter={(v) => won(Number(v))} />
                      <Bar
                        dataKey="value"
                        name="금액"
                        radius={[4, 4, 0, 0]}
                        isAnimationActive={false}
                      >
                        {data.profit.map((p, i) => (
                          <Cell
                            key={p.name}
                            fill={profitColor(p.name, p.value)}
                          />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
                <div className="fo-profit-note">
                  <span>매출액 − 원가 − 비용</span>
                  <strong>{won(data.income)}</strong>
                  <Link to="/accounting/income">손익계산서 보기 ↗</Link>
                </div>
              </Card>
            </div>
            <div className="fo-chart-grid">
              <Card
                title="매출 · 매출총이익 추이"
                note="최근 6개월 · 등록 전표가 없는 월은 0"
              >
                <div className="fo-chart">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart
                      data={data.months}
                      margin={{ left: 0, right: 12, top: 10, bottom: 0 }}
                    >
                      <CartesianGrid vertical={false} stroke="#efefef" />
                      <XAxis
                        dataKey="month"
                        tickFormatter={(s) => String(s).slice(5) + "월"}
                        tick={tick}
                        axisLine={false}
                        tickLine={false}
                      />
                      <YAxis
                        width={55}
                        tickFormatter={(n) => shortWon(Number(n))}
                        tick={tick}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip formatter={(v) => won(Number(v))} />
                      <Legend wrapperStyle={{ fontSize: 12 }} />
                      <Line
                        dataKey="revenue"
                        name="매출액"
                        stroke={ACCENT}
                        strokeWidth={2}
                        dot={{ r: 3 }}
                        isAnimationActive={false}
                      />
                      <Line
                        dataKey="grossProfit"
                        name="매출총이익"
                        stroke="#6b7280"
                        strokeWidth={2}
                        dot={{ r: 3 }}
                        isAnimationActive={false}
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </Card>
              <Card
                title="자금 유입 · 유출"
                note="최근 6개월 · 보통예금 계정의 차변/대변"
              >
                <div className="fo-chart">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart
                      data={data.months}
                      margin={{ left: 0, right: 12, top: 10, bottom: 0 }}
                    >
                      <CartesianGrid vertical={false} stroke="#efefef" />
                      <XAxis
                        dataKey="month"
                        tickFormatter={(s) => String(s).slice(5) + "월"}
                        tick={tick}
                        axisLine={false}
                        tickLine={false}
                      />
                      <YAxis
                        width={55}
                        tickFormatter={(n) => shortWon(Number(n))}
                        tick={tick}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip formatter={(v) => won(Number(v))} />
                      <Legend wrapperStyle={{ fontSize: 12 }} />
                      <Bar
                        dataKey="inflow"
                        name="유입"
                        fill={ACCENT}
                        radius={[3, 3, 0, 0]}
                        isAnimationActive={false}
                      />
                      <Bar
                        dataKey="outflow"
                        name="유출"
                        fill={NEUTRAL}
                        radius={[3, 3, 0, 0]}
                        isAnimationActive={false}
                      />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </Card>
              <Card
                title="예산 대비 실적"
                note="동일한 기간·계정 기준의 비교 자료 필요"
              >
                <div className="fo-pending">
                  <IconChartPie size={30} stroke={1.3} />
                  <b>예산 데이터 연동 전</b>
                  <p>
                    기간별 예산과 실적이 연결되면
                    <br />
                    비교 차트가 표시됩니다.
                  </p>
                  <span>비교 데이터 없음</span>
                </div>
              </Card>
            </div>
            <section className="panel fo-reconciliation">
              <div className="panel-heading">
                <div>
                  <h2>장부 대사 현황</h2>
                  <p>
                    전표 차대변 확인과 보조원장 대사를 구분해 표시합니다 · 현재{" "}
                    {data.postedCount}건의 전기 전표 기준
                  </p>
                </div>
                <span className="fo-pending-label">보조원장 연동 전</span>
              </div>
              <div className="data-table">
                <div className="table-scroll">
                  <Table miw={720} highlightOnHover={false}>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th style={{ width: 240 }}>대사 항목</Table.Th>
                        <Table.Th style={{ width: 130 }}>상태</Table.Th>
                        <Table.Th data-align="end" style={{ width: 160 }}>
                          차이
                        </Table.Th>
                        <Table.Th>근거 · 확인</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      <Table.Tr>
                        <Table.Td>전표 차변 ↔ 대변</Table.Td>
                        <Table.Td>
                          <StatusBadge
                            status={
                              data.postedCount === 0
                                ? "대기"
                                : data.debit === data.credit
                                  ? "정상"
                                  : "부족"
                            }
                          />
                        </Table.Td>
                        <Table.Td data-align="end">
                          {(data.debit - data.credit).toLocaleString("ko-KR")}
                        </Table.Td>
                        <Table.Td>
                          <Link
                            className="text-link"
                            to="/accounting/trial-balance"
                          >
                            합계잔액시산표에서 확인 ↗
                          </Link>
                        </Table.Td>
                      </Table.Tr>
                      {[
                        "매출채권 ↔ 총계정원장",
                        "매입채무 ↔ 총계정원장",
                        "재고장 ↔ 총계정원장",
                      ].map((label) => (
                        <Table.Tr key={label}>
                          <Table.Td>{label}</Table.Td>
                          <Table.Td>
                            <span className="status-badge" data-tone="neutral">
                              <i aria-hidden="true" />
                              연동 전
                            </span>
                          </Table.Td>
                          <Table.Td data-align="end" className="muted">
                            —
                          </Table.Td>
                          <Table.Td className="muted">
                            보조원장 연동 후 잔액을 비교합니다. 대사 결과를
                            정상으로 가정하지 않습니다.
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </div>
              </div>
            </section>
          </div>
        )
      )}
    </>
  );
}
