import { Link } from "react-router-dom";
import { Button, Select, Skeleton, Alert } from "@mantine/core";
import {
  IconArrowUpRight,
  IconArrowRight,
  IconChartBar,
  IconShoppingCart,
  IconBox,
  IconFileCheck,
  IconChevronRight,
  IconCalendar,
  IconSparkles,
  IconAlertTriangle,
  IconClock,
  IconCircleCheck,
  IconTruckDelivery,
  IconPlus,
  IconBuildingFactory2,
  IconReceipt,
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
import { KpiCard } from "../components/KpiCard";
import { StatusBadge } from "../components/StatusBadge";
import { shortWon, won } from "../domain/helpers";
import { useDashboard, monthlyTrend } from "../features/reporting/api";
import { useSession } from "../app/AppLayout";
import { documentUrl } from "../components/DocumentDetails";
export function DashboardPage() {
  const { actor, openAssistant } = useSession();
  const query = useDashboard();
  const data = query.data;
  const [period, setPeriod] = useState("6");
  if (query.isLoading)
    return (
      <div className="dashboard-skeleton">
        <Skeleton h={65} />
        <div className="kpi-grid dashboard-kpis">
          {[1, 2, 3, 4].map((n) => (
            <Skeleton key={n} h={140} />
          ))}
        </div>
        <Skeleton h={350} />
      </div>
    );
  if (query.error)
    return (
      <Alert color="red" title="현황을 불러오지 못했습니다">
        {query.error.message}
        <Button onClick={() => query.refetch()}>다시 시도</Button>
      </Alert>
    );
  const pending =
    data?.approvals.filter((r) => ["결재중", "상신"].includes(r.status)) ?? [];
  const low = data?.stock.filter((r) => r.status === "부족") ?? [];
  const late =
    data?.purchases.filter(
      (r) =>
        r.dueDate < "2026-09-29" &&
        !["입고완료", "지급완료"].includes(r.status),
    ) ?? [];
  return (
    <>
      <PageHeader
        title="대시보드"
        description={`${actor}님, 안녕하세요. 오늘의 업무 흐름을 한눈에 확인하세요.`}
        actions={
          <>
            <span className="date-pill">
              <IconCalendar size={14} /> 2026. 09. 29 <span>화요일</span>
            </span>
            <Button
              variant="default"
              component={Link}
              to="/reporting/overview"
              rightSection={<IconArrowUpRight size={14} />}
            >
              보고서 보기
            </Button>
          </>
        }
      />
      <div className="dashboard-greeting">
        <span>
          <span className="online-dot" />
          오늘 확인할 업무가 있습니다
        </span>
        <b>결재 진행 {pending.length}건</b>
        <span className="banner-divider" />
        <b>부족재고 {low.length}건</b>
        <Link to="/approval/pending">
          업무 확인하기 <IconArrowRight size={14} />
        </Link>
      </div>
      <div className="kpi-grid dashboard-kpis">
        <KpiCard
          label="등록 판매주문액"
          value={
            <>
              {shortWon(data?.revenue ?? 0)}
              <small>원</small>
            </>
          }
          icon={<IconChartBar size={20} />}
          detail={
            <>
              <b>{data?.sales.length ?? 0}건</b> 판매주문 기준
            </>
          }
        />
        <KpiCard
          label="등록 구매발주액"
          value={
            <>
              {shortWon(data?.purchaseAmount ?? 0)}
              <small>원</small>
            </>
          }
          icon={<IconShoppingCart size={20} />}
          tone="blue"
          detail={
            <>
              <b>{data?.purchases.length ?? 0}건</b> 구매발주 기준
            </>
          }
        />
        <KpiCard
          label="재고 평가액"
          value={
            <>
              {shortWon(data?.stockValue ?? 0)}
              <small>원</small>
            </>
          }
          icon={<IconBox size={20} />}
          tone="violet"
          detail={<>전체 창고 보유 재고 기준</>}
        />
        <KpiCard
          label="진행 중인 결재"
          value={
            <>
              {pending.length}
              <small>건</small>
            </>
          }
          icon={<IconFileCheck size={20} />}
          tone="orange"
          detail={
            <>
              <span className="orange-text">확인이 필요합니다</span> · 상신 /
              결재중
            </>
          }
        />
      </div>
      <div className="dashboard-top-grid">
        <section className="panel revenue-panel">
          <div className="panel-heading">
            <div>
              <h2>매출 · 매입 추이</h2>
              <p>월별 실적 요약 · 데모 분석 데이터</p>
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
              <i />
              매출
            </span>
            <span>
              <i />
              매입
            </span>
            <small>단위: 백만원</small>
          </div>
          <div className="revenue-chart">
            <ResponsiveContainer width="100%" height={215}>
              <AreaChart
                data={monthlyTrend.slice(-Number(period))}
                margin={{ top: 12, right: 22, left: -16, bottom: 0 }}
              >
                <defs>
                  <linearGradient id="revenue-fill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#262626" stopOpacity={0.12} />
                    <stop offset="100%" stopColor="#262626" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid
                  stroke="#f0f0f0"
                  vertical={false}
                  strokeDasharray="3 3"
                />
                <XAxis
                  dataKey="month"
                  axisLine={false}
                  tickLine={false}
                  tick={{ fill: "#9e9e9e", fontSize: 10 }}
                  dy={8}
                />
                <YAxis
                  axisLine={false}
                  tickLine={false}
                  tick={{ fill: "#a9a9a9", fontSize: 10 }}
                  domain={[0, 600]}
                  ticks={[0, 150, 300, 450, 600]}
                />
                <Tooltip
                  formatter={(value, name) => [
                    `${value}백만원`,
                    name === "revenue" ? "매출" : "매입",
                  ]}
                  contentStyle={{
                    borderRadius: 8,
                    border: "1px solid #ebebeb",
                    fontSize: 12,
                  }}
                />
                <Area
                  name="revenue"
                  type="monotone"
                  dataKey="revenue"
                  stroke="#262626"
                  strokeWidth={2.5}
                  fill="url(#revenue-fill)"
                  isAnimationActive={false}
                />
                <Area
                  name="cost"
                  type="monotone"
                  dataKey="cost"
                  stroke="#9e9e9e"
                  strokeWidth={2}
                  fill="transparent"
                  strokeDasharray="5 4"
                  isAnimationActive={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
          <div className="chart-note">
            <IconCircleCheck size={13} /> 9월 매출 목표 달성률 <b>97.2%</b>
            <span>목표 500백만원 · 분석용 고정 시나리오</span>
          </div>
        </section>
        <section className="panel todo-panel">
          <div className="panel-heading">
            <div>
              <h2>오늘의 체크리스트</h2>
              <p>놓치지 말아야 할 주요 업무</p>
            </div>
            <span className="tiny-badge">TO DO</span>
          </div>
          <Link to="/approval/pending" className="todo-row">
            <span className="todo-icon amber">
              <IconFileCheck size={19} />
            </span>
            <div>
              <b>결재 진행 문서</b>
              <small>처리를 기다리는 문서가 있어요</small>
            </div>
            <strong>
              {pending.length}
              <small>건</small>
            </strong>
            <IconChevronRight size={15} />
          </Link>
          <Link to="/inventory/shortage" className="todo-row">
            <span className="todo-icon red">
              <IconAlertTriangle size={19} />
            </span>
            <div>
              <b>안전재고 미달 품목</b>
              <small>추가 발주가 필요합니다</small>
            </div>
            <strong>
              {low.length}
              <small>건</small>
            </strong>
            <IconChevronRight size={15} />
          </Link>
          <Link to="/purchasing/orders" className="todo-row">
            <span className="todo-icon blue">
              <IconTruckDelivery size={19} />
            </span>
            <div>
              <b>납기 지연 발주</b>
              <small>거래처 납기일을 확인하세요</small>
            </div>
            <strong>
              {late.length}
              <small>건</small>
            </strong>
            <IconChevronRight size={15} />
          </Link>
          <button className="todo-ai" onClick={openAssistant}>
            <IconSparkles size={17} />
            <span>AI에게 오늘의 업무를 물어보세요</span>
            <IconArrowRight size={14} />
          </button>
        </section>
      </div>
      <div className="dashboard-middle-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>
                결재 진행 문서{" "}
                <span className="count-pill">{pending.length}</span>
              </h2>
              <p>우리 회사의 진행 중인 결재를 확인하세요</p>
            </div>
            <Link to="/approval/inbox">
              전체 보기 <span>↗</span>
            </Link>
          </div>
          <div className="compact-table-scroll">
            <table className="compact-table">
              <thead>
                <tr>
                  <th>문서명</th>
                  <th>기안자</th>
                  <th>금액</th>
                  <th>상태</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {pending.slice(0, 4).map((r) => (
                  <tr key={r.id}>
                    <td>
                      <Link to={documentUrl("approvals", r.id)}>
                        <b>{r.title}</b>
                        <small>
                          {r.id} · {r.date}
                        </small>
                      </Link>
                    </td>
                    <td>{r.owner}</td>
                    <td className="money">{won(r.amount)}</td>
                    <td>
                      <StatusBadge status={r.status} />
                    </td>
                    <td>
                      <Link
                        to={documentUrl("approvals", r.id)}
                        aria-label={`${r.id} 보기`}
                      >
                        <IconChevronRight size={14} />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
        <section className="panel shortcuts-panel">
          <div className="panel-heading">
            <div>
              <h2>빠른 업무 시작</h2>
              <p>자주 사용하는 업무 바로가기</p>
            </div>
            <IconPlus size={17} color="#262626" />
          </div>
          <div className="shortcut-grid">
            {[
              {
                name: "기안 작성",
                sub: "새로운 결재 문서",
                to: "/approval/drafts?new=1",
                icon: IconFileCheck,
              },
              {
                name: "구매요청",
                sub: "필요한 자재 요청",
                to: "/purchasing/requests?new=1",
                icon: IconShoppingCart,
              },
              {
                name: "판매주문",
                sub: "고객 주문 등록",
                to: "/sales/orders?new=1",
                icon: IconReceipt,
              },
              {
                name: "작업지시",
                sub: "생산 계획 시작",
                to: "/manufacturing/orders?new=1",
                icon: IconBuildingFactory2,
              },
            ].map((s) => (
              <Link key={s.name} to={s.to}>
                <s.icon size={21} stroke={1.4} />
                <b>{s.name}</b>
                <small>{s.sub}</small>
              </Link>
            ))}
          </div>
        </section>
      </div>
      <section className="panel flow-overview">
        <div className="panel-heading">
          <div>
            <h2>구매 업무 흐름</h2>
            <p>요청부터 회계 반영까지, 연결된 업무를 확인하세요</p>
          </div>
          <Link to="/purchasing/requests">구매관리로 이동 ↗</Link>
        </div>
        <div className="pipeline">
          {[
            {
              name: "구매요청",
              eng: "PURCHASE REQUEST",
              count:
                data?.purchases.filter((r) => r.status === "작성중").length ??
                0,
              to: "/purchasing/requests",
            },
            {
              name: "전자결재",
              eng: "APPROVAL",
              count: pending.length,
              to: "/approval/pending",
            },
            {
              name: "구매발주",
              eng: "PURCHASE ORDER",
              count:
                data?.purchases.filter((r) => r.status === "확정").length ?? 0,
              to: "/purchasing/orders",
            },
            {
              name: "입고 · 매입",
              eng: "GOODS RECEIPT",
              count:
                data?.purchases.filter((r) => r.status === "부분입고").length ??
                0,
              to: "/purchasing/receipts",
            },
            {
              name: "지급 · 회계",
              eng: "PAYMENT",
              count:
                data?.purchases.filter((r) => r.status === "입고완료").length ??
                0,
              to: "/purchasing/payments",
            },
          ].map((p, i) => (
            <Link to={p.to} key={p.name}>
              <div className="pipeline-step">
                <span>0{i + 1}</span>
                <b>{p.name}</b>
                {i < 4 && <IconArrowRight size={15} />}
              </div>
              <small>{p.eng}</small>
              <div className="pipeline-count">
                {p.count}
                <span>건 {i === 1 ? "진행중" : "관련 발주"}</span>
              </div>
            </Link>
          ))}
        </div>
      </section>
      <div className="dashboard-bottom">
        <span>
          <IconClock size={12} /> 기준일 2026.09.29 · 실시간 Mock 저장소 반영
        </span>
        <span>HAN-GYEOL INDUSTRIES · K-FLOW ERP</span>
      </div>
    </>
  );
}
