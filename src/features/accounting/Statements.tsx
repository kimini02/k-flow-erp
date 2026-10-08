import { Fragment, type ReactNode } from "react";
import { Link } from "react-router-dom";

export type Balance = { account: string; debit: number; credit: number };
type Line = {
  kind: "group" | "section" | "account" | "subtotal" | "total" | "grand";
  label: string;
  roman?: string;
  account?: string;
  to?: string;
  detail?: number;
  value?: number;
};

const CLASSES = ["자산", "부채", "자본", "수익", "비용"];
const CLASS_BY_DIGIT: Record<string, string> = {
  "1": "자산",
  "2": "부채",
  "3": "자본",
  "4": "수익",
  "5": "비용",
  "6": "비용",
};
export const accountClass = (account: string) =>
  CLASS_BY_DIGIT[account[0]] ?? "기타";
// Demo chart of accounts: assets and expenses carry debit balances.
const natural = (b: Balance) =>
  /^[156]/.test(b.account) ? b.debit - b.credit : b.credit - b.debit;
export const amount = (n: number) =>
  n < 0
    ? `(${Math.abs(n).toLocaleString("ko-KR")})`
    : n.toLocaleString("ko-KR");
const sum = (rows: Balance[]) => rows.reduce((n, b) => n + natural(b), 0);
const accountName = (account: string) => account.slice(5);
const ledgerLink = (account: string) =>
  `/accounting/ledger?account=${encodeURIComponent(account)}`;
const accountLines = (rows: Balance[]): Line[] =>
  rows
    .filter((b) => natural(b) !== 0)
    .map((b) => ({
      kind: "account",
      label: accountName(b.account),
      account: b.account,
      to: ledgerLink(b.account),
      detail: natural(b),
    }));
const starts = (balances: Balance[], ...prefixes: string[]) =>
  balances.filter((b) => prefixes.some((p) => b.account.startsWith(p)));

export function incomeStatement(balances: Balance[]) {
  const revenue = starts(balances, "4"),
    cogs = starts(balances, "5"),
    sga = starts(balances, "6");
  const gross = sum(revenue) - sum(cogs);
  const operating = gross - sum(sga);
  const lines: Line[] = [
    { kind: "section", roman: "Ⅰ", label: "매출액", value: sum(revenue) },
    ...accountLines(revenue),
    { kind: "section", roman: "Ⅱ", label: "매출원가", value: sum(cogs) },
    ...accountLines(cogs),
    { kind: "subtotal", roman: "Ⅲ", label: "매출총이익", value: gross },
    { kind: "section", roman: "Ⅳ", label: "판매비와관리비", value: sum(sga) },
    ...accountLines(sga),
    { kind: "subtotal", roman: "Ⅴ", label: "영업이익", value: operating },
    { kind: "grand", roman: "Ⅵ", label: "당기순이익", value: operating },
  ];
  return { lines, income: operating };
}

export function balanceSheet(balances: Balance[]) {
  const assets = starts(balances, "1"),
    liabilities = starts(balances, "2"),
    capital = starts(balances, "3");
  const income = incomeStatement(balances).income;
  const assetTotal = sum(assets),
    liabilityTotal = sum(liabilities),
    equityTotal = sum(capital) + income;
  const lines: Line[] = [
    { kind: "group", label: "자산" },
    { kind: "section", roman: "Ⅰ", label: "유동자산", value: assetTotal },
    ...accountLines(assets),
    { kind: "total", label: "자산총계", value: assetTotal },
    { kind: "group", label: "부채" },
    { kind: "section", roman: "Ⅰ", label: "유동부채", value: liabilityTotal },
    ...accountLines(liabilities),
    { kind: "total", label: "부채총계", value: liabilityTotal },
    { kind: "group", label: "자본" },
    { kind: "section", roman: "Ⅰ", label: "자본금", value: sum(capital) },
    ...accountLines(capital),
    { kind: "section", roman: "Ⅱ", label: "이익잉여금", value: income },
    {
      kind: "account",
      label: "당기순이익",
      to: "/accounting/income",
      detail: income,
    },
    { kind: "total", label: "자본총계", value: equityTotal },
    {
      kind: "grand",
      label: "부채와자본총계",
      value: liabilityTotal + equityTotal,
    },
  ];
  return { lines, assetTotal, claimTotal: liabilityTotal + equityTotal };
}

export function StatementFrame({
  title,
  period,
  children,
  check,
  note,
}: {
  title: string;
  period: string;
  children: ReactNode;
  check?: { ok: boolean; label: string };
  note?: string;
}) {
  return (
    <section className="statement-sheet" aria-label={title}>
      <header className="statement-header">
        <h2>{title}</h2>
        <p>{period}</p>
        <div>
          <span>회사명 : 한결 인더스트리</span>
          <span>(단위 : 원)</span>
        </div>
      </header>
      {children}
      {(check || note) && (
        <footer className="statement-footer">
          {note && <p>{note}</p>}
          {check && (
            <span className="ledger-check" data-ok={check.ok || undefined}>
              {check.label}
            </span>
          )}
        </footer>
      )}
    </section>
  );
}

export function StatementTable({ lines }: { lines: Line[] }) {
  const cell = (n?: number) =>
    n === undefined ? null : (
      <span className={n < 0 ? "negative" : undefined}>{amount(n)}</span>
    );
  return (
    <table className="statement-table">
      <thead>
        <tr>
          <th>과 목</th>
          <th colSpan={2}>금 액</th>
        </tr>
      </thead>
      <tbody>
        {lines.map((l, i) => (
          <tr key={`${l.label}-${i}`} className={`line-${l.kind}`}>
            <td>
              {l.roman && <span className="roman">{l.roman}.</span>}
              {l.to ? (
                <Link to={l.to} className="statement-link">
                  {l.label}
                </Link>
              ) : (
                l.label
              )}
              {l.account && <small>{l.account.slice(0, 4)}</small>}
            </td>
            <td className="amount">{cell(l.detail)}</td>
            <td className="amount">{cell(l.value)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// 합계잔액시산표: debit balance | debit total | account | credit total | credit balance.
export function TrialBalanceTable({ balances }: { balances: Balance[] }) {
  const rows = [...balances].sort((a, b) => a.account.localeCompare(b.account));
  const debitBalance = (b: Balance) => Math.max(0, b.debit - b.credit);
  const creditBalance = (b: Balance) => Math.max(0, b.credit - b.debit);
  const total = (f: (b: Balance) => number) =>
    rows.reduce((n, b) => n + f(b), 0);
  return (
    <table className="statement-table trial-table">
      <thead>
        <tr>
          <th colSpan={2}>차 변</th>
          <th rowSpan={2} className="account-col">
            계 정 과 목
          </th>
          <th colSpan={2}>대 변</th>
        </tr>
        <tr>
          <th>잔 액</th>
          <th>합 계</th>
          <th>합 계</th>
          <th>잔 액</th>
        </tr>
      </thead>
      <tbody>
        {CLASSES.map((cls) => {
          const group = rows.filter((b) => accountClass(b.account) === cls);
          if (!group.length) return null;
          return (
            <Fragment key={cls}>
              <tr className="line-group">
                <td colSpan={5}>{cls}</td>
              </tr>
              {group.map((b) => (
                <tr key={b.account}>
                  <td className="amount">
                    {debitBalance(b) ? amount(debitBalance(b)) : ""}
                  </td>
                  <td className="amount">{b.debit ? amount(b.debit) : ""}</td>
                  <td className="account-col">
                    <Link to={ledgerLink(b.account)} className="statement-link">
                      {accountName(b.account)}
                    </Link>
                    <small>{b.account.slice(0, 4)}</small>
                  </td>
                  <td className="amount">{b.credit ? amount(b.credit) : ""}</td>
                  <td className="amount">
                    {creditBalance(b) ? amount(creditBalance(b)) : ""}
                  </td>
                </tr>
              ))}
            </Fragment>
          );
        })}
      </tbody>
      <tfoot>
        <tr className="line-grand">
          <td className="amount">{amount(total(debitBalance))}</td>
          <td className="amount">{amount(total((b) => b.debit))}</td>
          <td className="account-col">합 계</td>
          <td className="amount">{amount(total((b) => b.credit))}</td>
          <td className="amount">{amount(total(creditBalance))}</td>
        </tr>
      </tfoot>
    </table>
  );
}
