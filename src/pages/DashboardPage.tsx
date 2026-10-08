import { Link } from "react-router-dom";
import { Button, Select, Skeleton, Alert } from "@mantine/core";
import {
  IconArrowUpRight,
  IconChevronRight,
  IconFileCheck,
  IconShoppingCart,
  IconReceipt,
  IconBuildingFactory2,
} from "@tabler/icons-react";
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { useState } from "react";
import { PageHeader } from "../components/PageHeader";
import { StatusBadge } from "../components/StatusBadge";
import { shortWon, won } from "../domain/helpers";
import { useDashboard, monthlyTrend } from "../features/reporting/api";
import { useSession } from "../app/AppLayout";
import { documentUrl } from "../components/DocumentDetails";

const DEMO_TODAY = "2026-09-29";
const ACCENT = "#2b59c3";
const NEUTRAL = "#9ca3af";
type Tone = "waiting" | "danger" | "info";

// Order: what needs me today -> where the business stands -> how it is trending.
export function DashboardPage() {
  const { actor } = useSession();
  const query = useDashboard();
  const data = query.data;
  const [period, setPeriod] = useState("6");
  if (query.isLoading)
    return (
      <div className="dashboard-skeleton">
        <Skeleton h={52} />
        <Skeleton h={96} />
        <Skeleton h={64} />
        <Skeleton h={320} />
      </div>
    );
  if (query.error || !data)
    return (
      <Alert color="red" title="현황을 불러오지 못했습니다">
        {query.error?.message}
        <Button onClick={() => query.refetch()}>다시 시도</Button>
      </Alert>
    );
  const pending = data.approvals.filter((r) =>
    ["결재중", "상신"].includes(r.status),
  );
  const myTurn = pending.filter(
    (r) => r.approvals.find((s) => s.status === "대기")?.name === actor,
  );
  const low = data.stock.filter((r) => r.status === "부족");
  const late = data.purchases.filter(
    (r) =>
      r.dueDate < DEMO_TODAY && !["입고완료", "지급완료"].includes(r.status),
  );
  const producing = data.production.filter((r) => r.status === "생산중");
  const tiles: {
    label: string;
    count: number;
    note: string;
    to: string;
    tone: Tone;
  }[] = [
    {
      label: "내 결재 차례",
      count: myTurn.length,
      note: `${actor}님이 승인할 문서`,
      to: "/approval/inbox",
      tone: "waiting",
    },
    {
      label: "결재 진행 문서",
      count: pending.length,
      note: "상신 · 결재중 전체",
      to: "/approval/pending",
      tone: "info",
    },
    {
      label: "안전재고 미달",
      count: low.length,
      note: "가용재고 < 안전재고",
      to: "/inventory/shortage",
      tone: "danger",
    },
    {
      label: "납기 지연 발주",
      count: late.length,
      note: "납기일 경과 · 미입고",
      to: "/purchasing/orders",
      tone: "danger",
    },
  ];
  const kpis = [
    ["판매주문액", shortWon(data.revenue), "원", `${data.sales.length}건`],
    [
      "구매발주액",
      shortWon(data.purchaseAmount),
      "원",
      `${data.purchases.length}건`,
    ],
    ["재고 평가액", shortWon(data.stockValue), "원", "전체 창고"],
    [
      "생산중 작업지시",
      producing.length,
      "건",
      `전체 ${data.production.length}건`,
    ],
  ];
  const pipeline = [
    {
      name: "구매요청",
      count: data.requests.filter((r) => r.status === "승인대기").length,
      state: "결재 대기",
      to: "/purchasing/requests",
    },
    {
      name: "구매발주",
      count: data.purchases.filter((r) =>
        ["확정", "부분입고"].includes(r.status),
      ).length,
      state: "입고 대기",
      to: "/purchasing/orders",
    },
    {
      name: "입고",
      count: data.receipts.filter((r) => r.status === "작성중").length,
      state: "확정 대기",
      to: "/purchasing/receipts",
    },
    {
      name: "매입",
      count: data.bills.filter((r) => r.status === "작성중").length,
      state: "전기 대기",
      to: "/purchasing/bills",
    },
    {
      name: "지급",
      count: data.payables.filter((r) => Number(r.values.balance ?? 0) > 0)
        .length,
      state: "지급 대기",
      to: "/purchasing/payables",
    },
  ];
  const shortcuts = [
    { name: "기안 작성", to: "/approval/drafts?new=1", icon: IconFileCheck },
    {
      name: "구매요청",
      to: "/purchasing/requests?new=1",
      icon: IconShoppingCart,
    },
    { name: "판매주문", to: "/sales/orders?new=1", icon: IconReceipt },
    {
      name: "작업지시",
      to: "/manufacturing/orders?new=1",
      icon: IconBuildingFactory2,
    },
  ];
  return (
    <>
      <PageHeader
        title="대시보드"
        description={`${actor}님 · 2026년 9월 29일(화) 기준 업무 현황`}
        actions={
          <Button
            variant="default"
            component={Link}
            to="/reporting/overview"
            rightSection={<IconArrowUpRight size={14} />}
          >
            경영 분석
          </Button>
        }
      />
      <section className="work-tiles" aria-label="오늘 처리할 업무">
        {tiles.map((t) => (
          <Link
            key={t.label}
            to={t.to}
            className="work-tile"
            data-tone={t.count ? t.tone : undefined}
          >
            <span>{t.label}</span>
            <strong>
              {t.count}
              <small>건</small>
            </strong>
            <small>{t.count ? t.note : "처리할 항목 없음"}</small>
            <IconChevronRight size={16} aria-hidden="true" />
          </Link>
        ))}
      </section>
      <dl className="summary-strip">
        {kpis.map(([label, value, unit, note]) => (
          <div key={String(label)}>
            <dt>
              {label} <span className="muted">· {note}</span>
            </dt>
            <dd>
              {value}
              <small>{unit}</small>
            </dd>
          </div>
        ))}
      </dl>
      <div className="dashboard-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>매출 · 매입 추이</h2>
              <p>월별 실적 · 데모 분석 시나리오 · 단위 백만원</p>
            </div>
            <Select
              aria-label="차트 조회 기간"
              data={[
                { value: "6", label: "최근 6개월" },
                { value: "3", label: "최근 3개월" },
              ]}
              value={period}
              onChange={(v) => setPeriod(v ?? "6")}
              allowDeselect={false}
              size="xs"
              w={112}
            />
          </div>
          <div className="chart-legend">
            <span>
              <i style={{ background: ACCENT }} />
              매출
            </span>
            <span>
              <i style={{ background: NEUTRAL }} />
              매입
            </span>
          </div>
          <div className="revenue-chart">
            <ResponsiveContainer width="100%" height={230}>
              <AreaChart
                data={monthlyTrend.slice(-Number(period))}
                margin={{ top: 12, right: 20, left: -12, bottom: 0 }}
              >
                <defs>
                  <linearGradient id="revenue-fill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={ACCENT} stopOpacity={0.14} />
                    <stop offset="100%" stopColor={ACCENT} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#eceef1" vertical={false} />
                <XAxis
                  dataKey="month"
                  axisLine={false}
                  tickLine={false}
                  tick={{ fill: "#6b7280", fontSize: 12 }}
                  dy={6}
                />
                <YAxis
                  axisLine={false}
                  tickLine={false}
                  tick={{ fill: "#6b7280", fontSize: 12 }}
                  domain={[0, 600]}
                  ticks={[0, 150, 300, 450, 600]}
                />
                <Tooltip
                  formatter={(value, name) => [
                    `${value}백만원`,
                    name === "revenue" ? "매출" : "매입",
                  ]}
                  contentStyle={{
                    borderRadius: 4,
                    border: "1px solid #e4e7eb",
                    fontSize: 12,
                  }}
                />
                <Area
                  name="revenue"
                  type="monotone"
                  dataKey="revenue"
                  stroke={ACCENT}
                  strokeWidth={2.5}
                  fill="url(#revenue-fill)"
                  isAnimationActive={false}
                />
                <Area
                  name="cost"
                  type="monotone"
                  dataKey="cost"
                  stroke={NEUTRAL}
                  strokeWidth={2}
                  fill="transparent"
                  strokeDasharray="5 4"
                  isAnimationActive={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
          <p className="chart-note">
            9월 매출 목표 달성률 <b>97.2%</b> · 목표 500백만원 (분석용 고정
            시나리오)
          </p>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>
                결재 진행 문서{" "}
                <span className="count-pill">{pending.length}</span>
              </h2>
              <p>상신 · 결재중 문서 중 최근 5건</p>
            </div>
            <Link to="/approval/pending">전체 보기 ↗</Link>
          </div>
          <ul className="dashboard-list">
            {pending.slice(0, 5).map((r) => {
              const step = r.approvals.find((s) => s.status === "대기");
              return (
                <li key={r.id}>
                  <Link to={documentUrl("approvals", r.id)}>
                    <div>
                      <b>{r.title}</b>
                      <small>
                        {r.id} · 기안 {r.owner}
                        {step ? ` · ${step.name} 차례` : ""}
                      </small>
                    </div>
                    <span className="money">
                      {r.amount ? won(r.amount) : "—"}
                    </span>
                    <StatusBadge status={r.status} />
                  </Link>
                </li>
              );
            })}
          </ul>
        </section>
      </div>
      <div className="dashboard-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>구매 업무 흐름</h2>
              <p>단계별로 다음 처리를 기다리는 문서</p>
            </div>
            <Link to="/purchasing/requests">구매관리 ↗</Link>
          </div>
          <ol className="pipeline">
            {pipeline.map((p, i) => (
              <li key={p.name}>
                <Link to={p.to}>
                  <span className="pipeline-step">
                    {i + 1}. {p.name}
                  </span>
                  <strong data-active={p.count > 0 || undefined}>
                    {p.count}
                    <small>건</small>
                  </strong>
                  <small>{p.state}</small>
                </Link>
              </li>
            ))}
          </ol>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>빠른 업무 시작</h2>
              <p>새 문서 작성</p>
            </div>
          </div>
          <div className="shortcut-list">
            {shortcuts.map((s) => (
              <Link key={s.name} to={s.to}>
                <s.icon size={18} stroke={1.6} aria-hidden="true" />
                {s.name}
                <IconChevronRight size={14} aria-hidden="true" />
              </Link>
            ))}
          </div>
        </section>
      </div>
    </>
  );
}
