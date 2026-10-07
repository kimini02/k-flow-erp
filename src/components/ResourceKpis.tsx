import {
  IconFileDescription,
  IconClock,
  IconCircleCheck,
  IconWallet,
} from "@tabler/icons-react";
import type { RecordData, Resource } from "../domain/types";
import { shortWon, sum } from "../domain/helpers";
import { KpiCard } from "./KpiCard";
type Metric = {
  label: string;
  value: string | number;
  unit: string;
  detail: string;
};
export function ResourceKpis({
  resource,
  rows,
}: {
  resource: Resource;
  rows: RecordData[];
}) {
  const count = (statuses: string[]) =>
    rows.filter((r) => statuses.includes(r.status)).length;
  const total = (key: string) => sum(rows, key);
  let metrics: Metric[] = [
    {
      label: `전체 ${resource.singular}`,
      value: rows.length,
      unit: "건",
      detail: "등록된 전체 데이터",
    },
    {
      label: resource.kind === "approval" ? "결재 진행중" : "처리 필요",
      value: count([
        "결재중",
        "상신",
        "승인대기",
        "작성중",
        "미지급",
        "미수금",
        "부족",
      ]),
      unit: "건",
      detail: "확인이 필요한 업무",
    },
    {
      label: resource.kind === "approval" ? "승인완료" : "완료 / 정상",
      value: count([
        "승인완료",
        "입고완료",
        "생산완료",
        "전기완료",
        "정상",
        "완료",
        "활성",
        "사용중",
        "지급완료",
        "수금완료",
        "출고완료",
      ]),
      unit: "건",
      detail: "정상 처리된 업무",
    },
    {
      label: "전체 공급가액",
      value: shortWon(total("amount")),
      unit: "원",
      detail: "문서 금액 기준 합계",
    },
  ];
  if (resource.key === "stock")
    metrics = [
      {
        label: "재고 관리 항목",
        value: rows.length,
        unit: "건",
        detail: "품목 · 창고별 기준",
      },
      {
        label: "안전재고 미달",
        value: count(["부족"]),
        unit: "건",
        detail: "가용재고 < 안전재고",
      },
      {
        label: "가용재고 합계",
        value: total("available").toLocaleString(),
        unit: "EA",
        detail: "현재고에서 예약수량 제외",
      },
      {
        label: "재고 평가액",
        value: shortWon(total("amount")),
        unit: "원",
        detail: "보유수량 × 단가",
      },
    ];
  else if (resource.key === "work-orders")
    metrics = [
      {
        label: "작업지시",
        value: rows.length,
        unit: "건",
        detail: "등록된 전체 작업지시",
      },
      {
        label: "생산중",
        value: count(["생산중"]),
        unit: "건",
        detail: "현재 가동 중인 작업",
      },
      {
        label: "생산 실적",
        value: total("produced").toLocaleString(),
        unit: "EA",
        detail: `계획 ${total("qty").toLocaleString()} EA`,
      },
      {
        label: "계획 달성률",
        value: total("qty")
          ? Math.round((total("produced") / total("qty")) * 100)
          : 0,
        unit: "%",
        detail: "실적 ÷ 계획수량",
      },
    ];
  else if (resource.key === "employees")
    metrics = [
      {
        label: "등록 직원",
        value: rows.length,
        unit: "명",
        detail: "데모에 등록된 직원",
      },
      {
        label: "재직",
        value: count(["재직"]),
        unit: "명",
        detail: "현재 재직자",
      },
      {
        label: "휴직",
        value: count(["휴직"]),
        unit: "명",
        detail: "휴직 처리된 직원",
      },
      {
        label: "소속 부서",
        value: new Set(rows.map((r) => r.department)).size,
        unit: "개",
        detail: "등록 직원의 부서",
      },
    ];
  else if (resource.key === "attendance")
    metrics = [
      {
        label: "근태 기록",
        value: rows.length,
        unit: "건",
        detail: "등록된 근무 기록",
      },
      {
        label: "정상 출근",
        value: count(["정시"]),
        unit: "건",
        detail: "정시 출근 기록",
      },
      {
        label: "지각",
        value: count(["지각"]),
        unit: "건",
        detail: "지각 기록",
      },
      {
        label: "근무시간 합계",
        value: total("hours"),
        unit: "시간",
        detail: "등록 근무시간 기준",
      },
    ];
  else if (resource.key === "leave")
    metrics[3] = {
      label: "신청 연차 합계",
      value: total("days"),
      unit: "일",
      detail: "전체 신청 문서 기준",
    };
  else if (resource.key === "payroll")
    metrics = [
      {
        label: "급여 문서",
        value: rows.length,
        unit: "건",
        detail: "부서 · 급여월별 문서",
      },
      {
        label: "지급총액",
        value: shortWon(total("gross")),
        unit: "원",
        detail: "공제 전 급여",
      },
      {
        label: "공제액",
        value: shortWon(total("deduction")),
        unit: "원",
        detail: "세금 및 보험료 합계",
      },
      {
        label: "실지급액",
        value: shortWon(total("net")),
        unit: "원",
        detail: "지급총액 − 공제액",
      },
    ];
  else if (["payables", "receivables"].includes(resource.key)) {
    metrics[1] = {
      label: "연체 문서",
      value: count(["연체"]),
      unit: "건",
      detail: "지급 · 수금 기한 초과",
    };
    metrics[3] = {
      label: resource.key === "payables" ? "미지급 잔액" : "미수금 잔액",
      value: shortWon(total("balance")),
      unit: "원",
      detail: "기지급 · 수금액 제외",
    };
  } else if (resource.kind === "master") {
    metrics = [
      {
        label: `등록 ${resource.singular}`,
        value: rows.length,
        unit: "건",
        detail: "전체 기준정보",
      },
      {
        label: "사용중 / 활성",
        value: count(["사용중", "활성", "진행중"]),
        unit: "건",
        detail: "현재 사용 가능한 자료",
      },
      {
        label: "비활성 / 마감",
        value: count(["비활성", "마감"]),
        unit: "건",
        detail: "중지 또는 마감된 자료",
      },
      {
        label: "최근 등록일",
        value:
          [...rows].sort((a, b) => b.date.localeCompare(a.date))[0]?.date ??
          "—",
        unit: "",
        detail: "등록일 기준",
      },
    ];
  } else if (resource.kind === "audit") {
    metrics = [
      {
        label: "전체 이벤트",
        value: rows.length,
        unit: "건",
        detail: "업무 변경 기록",
      },
      {
        label: "수행자",
        value: new Set(rows.map((r) => r.owner)).size,
        unit: "명",
        detail: "로그에 기록된 사용자",
      },
      {
        label: "이벤트 유형",
        value: new Set(rows.map((r) => r.values.event)).size,
        unit: "개",
        detail: "등록 · 승인 · 전기 등",
      },
      {
        label: "오늘 이벤트",
        value: rows.filter((r) => r.date === "2026-09-29").length,
        unit: "건",
        detail: "2026.09.29 기준",
      },
    ];
  }
  const icons = [IconFileDescription, IconClock, IconCircleCheck, IconWallet];
  return (
    <div className="kpi-grid">
      {metrics.map((m, i) => {
        const Icon = icons[i];
        return (
          <KpiCard
            key={m.label}
            label={m.label}
            value={
              <>
                <span
                  className={
                    String(m.value).length > 9 ? "small-kpi-value" : ""
                  }
                >
                  {m.value}
                </span>
                <small>{m.unit}</small>
              </>
            }
            detail={m.detail}
            icon={<Icon size={19} />}
            tone={["neutral", "orange", "neutral", "blue"][i]}
          />
        );
      })}
    </div>
  );
}
