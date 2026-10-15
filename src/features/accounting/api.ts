import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
export function useAccounting(from: string, to: string) {
  return useQuery({
    queryKey: ["erp", "accounting-report", from, to],
    queryFn: async () => {
      const [journal, accounts] = await Promise.all([
        api.list("journals", { from, to, size: 100000 }),
        api.list("accounts", { size: 100000 }),
      ]);
      const posted = journal.rows.filter(
        (r) => r.status === "전기완료" || r.status === "역분개완료",
      );
      const balances = new Map<
        string,
        { account: string; debit: number; credit: number }
      >();
      for (const doc of posted)
        for (const line of doc.journal) {
          const old = balances.get(line.account) ?? {
            account: line.account,
            debit: 0,
            credit: 0,
          };
          old.debit += line.debit;
          old.credit += line.credit;
          balances.set(line.account, old);
        }
      return {
        journals: posted,
        balances: [...balances.values()].sort((a, b) =>
          a.account.localeCompare(b.account),
        ),
        accounts: accounts.rows,
      };
    },
  });
}
