import { moduleConfig } from "../../domain/config";
export const reportingModule = moduleConfig(
  "reporting",
  "보고서/분석",
  "REPORTS & ANALYTICS",
  [
    ["overview", "경영 분석"],
    ["purchasing", "구매 분석"],
    ["sales", "매출 분석"],
    ["inventory", "재고 분석"],
    ["production", "생산 분석"],
  ],
);
