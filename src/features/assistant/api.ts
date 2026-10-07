import { api } from "../../api/client";
import { resourceByKey } from "../../app/catalog";
import { blankRecord, sum, won } from "../../domain/helpers";
import { PARTNERS, ITEMS } from "../../domain/types";
import { documentUrl } from "../../components/DocumentDetails";
import type { ChatMessage } from "./types";
export const examples = [
  "이번 달 개발팀 지출 알려줘",
  "미결재 구매요청 보여줘",
  "재고 부족 품목 찾아줘",
  "지난달 대비 매출이 왜 감소했어?",
  "ABC전자 맥북 10대 구매요청 초안 만들어줘",
];
export async function mockReply(
  text: string,
  actor: string,
): Promise<Omit<ChatMessage, "id" | "role">> {
  await new Promise((r) => setTimeout(r, 350));
  if (/초안|만들|생성/.test(text)) {
    const resource = resourceByKey("purchase-requests")!;
    const row = blankRecord(resource, actor);
    row.values.reason = text;
    const partner = PARTNERS.find((p) => text.includes(p)) ?? "ABC전자";
    const qty = Number(text.match(/(\d+)\s*(대|개|EA)/i)?.[1] ?? 10);
    const item =
      ITEMS.find((i) => text.includes(i)) ??
      (text.includes("맥북") ? "맥북 Pro 14" : "정밀 제어 모듈 K-100");
    row.partner = partner;
    row.department = "개발팀";
    row.title = `개발팀 ${item} ${qty}대 구매요청`;
    row.lines = [
      { item, qty, price: item.includes("맥북") ? 2490000 : 120000 },
    ];
    row.amount = row.lines.reduce((n, l) => n + l.qty * l.price, 0);
    row.values.memo =
      "AI Assistant가 제안한 초안 · 금액 및 품목을 검토해 주세요.";
    return {
      text: "아래 구매요청 초안을 준비했습니다. 단가는 데모 기준 금액입니다. 내용을 확인한 후 생성 버튼을 눌러 주세요. 생성된 문서는 작성중 상태로 저장됩니다.",
      proposal: row,
    };
  }
  if (/미결재|결재/.test(text)) {
    const list = await api.list("purchase-requests", { size: 10000 });
    const rows = list.all.filter((r) => r.status === "승인대기");
    return {
      text: `현재 승인대기 중인 구매요청은 ${rows.length}건이며, 총 공급가액은 ${won(sum(rows))}입니다. 아래 문서에서 연결된 결재선을 확인할 수 있습니다.`,
      links: rows.slice(0, 4).map((r) => ({
        label: `${r.id} · ${r.title}`,
        url: documentUrl("purchase-requests", r.id),
      })),
    };
  }
  if (/재고|부족/.test(text)) {
    const list = await api.list("stock", { status: "부족", size: 10000 });
    return {
      text: `안전재고보다 가용재고가 적은 항목이 ${list.rows.length}건 있습니다. 창고별 보유량과 예약수량을 함께 확인해 주세요.`,
      links: list.rows.slice(0, 4).map((r) => ({
        label: `${r.title} · ${r.values.warehouse} · 가용 ${r.values.available} / 안전 ${r.values.safety}`,
        url: documentUrl("stock", r.id),
      })),
    };
  }
  if (/매출|감소/.test(text))
    return {
      text: "분석용 월별 시나리오에서 8월 매출은 421백만원, 9월은 486백만원으로 15.4% 증가했습니다. 현재 데이터에서는 매출 감소를 확인할 수 없습니다. 거래처별 주문 지연이나 반품 원인은 이 Mock 데이터만으로 판단할 수 없으므로 매출 분석과 반품 문서를 함께 확인해 주세요.",
      links: [{ label: "매출 분석 보고서 보기", url: "/reporting/sales" }],
    };
  if (/지출|개발팀/.test(text)) {
    const list = await api.list("payments-out", {
      from: "2026-09-01",
      to: "2026-09-30",
      size: 10000,
    });
    const rows = list.rows.filter((r) => r.department === "개발팀");
    return {
      text: `9월 개발팀의 등록 지급액은 ${won(sum(rows))}, 총 ${rows.length}건입니다. 지급 문서 기준이며 미지급 매입채무는 포함하지 않았습니다.`,
      links: rows.slice(0, 3).map((r) => ({
        label: `${r.id} · ${r.partner} · ${won(r.amount)}`,
        url: documentUrl("payments-out", r.id),
      })),
    };
  }
  return {
    text: "현재 데모에서는 부서별 지출, 미결재 구매요청, 부족재고, 매출 추이, 구매요청 초안을 확인할 수 있습니다. 아래 예시 질문 중 하나를 선택해 주세요.",
  };
}
