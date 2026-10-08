import { IconCheck, IconChevronRight } from "@tabler/icons-react";
import type { DocLink } from "../domain/types";

// Workflow step label -> resource that records that step.
const STEP_RESOURCE: Record<string, string> = {
  구매요청: "purchase-requests",
  전자결재: "approvals",
  구매발주: "purchase-orders",
  입고: "goods-receipts",
  매입: "purchase-bills",
  지급: "payments-out",
  회계전표: "journals",
  견적: "quotations",
  판매주문: "sales-orders",
  출고: "deliveries",
  매출: "invoices",
  수금: "payments-in",
  BOM: "boms",
  작업지시: "work-orders",
  "원재료 출고": "material-issues",
  "완제품 입고": "production-receipts",
};

// The flow shows what is actually linked to this document, not a fixed stage per screen.
export function DocumentFlow({
  steps,
  resource,
  links,
  onOpen,
}: {
  steps: string[];
  resource: string;
  links: DocLink[];
  onOpen: (resource: string, id: string) => void;
}) {
  return (
    <ol className="document-flow" aria-label="업무 흐름">
      {steps.map((step, i) => {
        const key = STEP_RESOURCE[step];
        const linked = key ? links.filter((l) => l.resource === key) : [];
        const state =
          key === resource ? "current" : linked.length ? "done" : "none";
        const label = (
          <>
            <span className="flow-mark" aria-hidden="true">
              {state === "done" ? <IconCheck size={11} /> : i + 1}
            </span>
            {step}
            {linked.length > 1 && <small>{linked.length}</small>}
          </>
        );
        return (
          <li key={step} data-state={state}>
            {state === "done" ? (
              <button
                type="button"
                title={linked.map((l) => l.id).join(", ")}
                aria-label={`${step} ${linked[0].id} 보기`}
                onClick={() => onOpen(linked[0].resource, linked[0].id)}
              >
                {label}
              </button>
            ) : (
              <span aria-current={state === "current" ? "step" : undefined}>
                {label}
              </span>
            )}
            {i < steps.length - 1 && (
              <IconChevronRight size={12} aria-hidden="true" />
            )}
          </li>
        );
      })}
    </ol>
  );
}
