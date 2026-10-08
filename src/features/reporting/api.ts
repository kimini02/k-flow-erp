import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { sum } from "../../domain/helpers";
export const monthlyTrend = [
  { month: "4월", revenue: 328, cost: 216 },
  { month: "5월", revenue: 392, cost: 264 },
  { month: "6월", revenue: 358, cost: 240 },
  { month: "7월", revenue: 456, cost: 298 },
  { month: "8월", revenue: 421, cost: 276 },
  { month: "9월", revenue: 486, cost: 312 },
];
export function useDashboard() {
  return useQuery({
    queryKey: ["erp", "dashboard"],
    queryFn: async () => {
      const [
        approval,
        purchase,
        sales,
        stock,
        production,
        audit,
        requests,
        receipts,
        bills,
        payables,
      ] = await Promise.all(
        [
          "approvals",
          "purchase-orders",
          "sales-orders",
          "stock",
          "work-orders",
          "audit-events",
          "purchase-requests",
          "goods-receipts",
          "purchase-bills",
          "payables",
        ].map((r) => api.list(r, { size: 10000 })),
      );
      return {
        approvals: approval.all,
        purchases: purchase.all,
        sales: sales.all,
        stock: stock.all,
        production: production.all,
        audit: audit.all,
        requests: requests.all,
        receipts: receipts.all,
        bills: bills.all,
        payables: payables.all,
        revenue: sum(sales.all),
        purchaseAmount: sum(purchase.all),
        stockValue: sum(stock.all),
      };
    },
  });
}
