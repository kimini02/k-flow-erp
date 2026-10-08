import { Fragment } from "react";
import { Table } from "@mantine/core";
import type { RecordData } from "../../domain/types";

type Totals = { account: string; debit: number; credit: number };
type Entry = {
  date: string;
  id: string;
  title: string;
  debit: number;
  credit: number;
};
const amount = (n: number) => (n ? n.toLocaleString("ko-KR") : "—");
const signed = (n: number) =>
  n < 0 ? `−${Math.abs(n).toLocaleString("ko-KR")}` : n.toLocaleString("ko-KR");
// Demo chart of accounts: 1 assets, 5 cost of sales, 6 expenses carry debit balances.
export const debitNormal = (account: string) => /^[156]/.test(account);

export function GeneralLedger({
  journals,
  opening,
  account,
  from,
  onOpen,
  selectedId,
}: {
  journals: RecordData[];
  opening: Totals[];
  account: string | null;
  from: string;
  onOpen: (entryId: string) => void;
  selectedId?: string | null;
}) {
  const entries = new Map<string, Entry[]>();
  for (const doc of journals)
    for (const line of doc.journal) {
      const list = entries.get(line.account) ?? [];
      list.push({
        date: doc.date,
        id: doc.id,
        title: doc.title,
        debit: line.debit,
        credit: line.credit,
      });
      entries.set(line.account, list);
    }
  const accounts = [
    ...new Set([
      ...entries.keys(),
      ...opening.filter((o) => o.debit !== o.credit).map((o) => o.account),
    ]),
  ]
    .filter((a) => !account || a === account)
    .sort((a, b) => a.localeCompare(b));
  return (
    <div className="data-table ledger-table">
      <div className="table-scroll">
        <Table miw={820} highlightOnHover={false}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th style={{ width: 112 }}>일자</Table.Th>
              <Table.Th style={{ width: 140 }}>전표번호</Table.Th>
              <Table.Th>적요</Table.Th>
              <Table.Th data-align="end" style={{ width: 150 }}>
                차변
              </Table.Th>
              <Table.Th data-align="end" style={{ width: 150 }}>
                대변
              </Table.Th>
              <Table.Th data-align="end" style={{ width: 160 }}>
                잔액
              </Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {accounts.map((acc) => {
              const sign = debitNormal(acc) ? 1 : -1;
              const op = opening.find((o) => o.account === acc);
              const openingBalance = op ? sign * (op.debit - op.credit) : 0;
              const lines = (entries.get(acc) ?? []).sort(
                (a, b) =>
                  a.date.localeCompare(b.date) || a.id.localeCompare(b.id),
              );
              let balance = openingBalance;
              const debit = lines.reduce((n, l) => n + l.debit, 0);
              const credit = lines.reduce((n, l) => n + l.credit, 0);
              return (
                <Fragment key={acc}>
                  <Table.Tr className="account-row">
                    <Table.Td colSpan={6}>
                      <b>{acc}</b>
                      <small>
                        {sign > 0 ? "차변 잔액 계정" : "대변 잔액 계정"}
                      </small>
                    </Table.Td>
                  </Table.Tr>
                  <Table.Tr className="carry-row">
                    <Table.Td>{from}</Table.Td>
                    <Table.Td />
                    <Table.Td>전기이월</Table.Td>
                    <Table.Td data-align="end" />
                    <Table.Td data-align="end" />
                    <Table.Td data-align="end">
                      {signed(openingBalance)}
                    </Table.Td>
                  </Table.Tr>
                  {lines.map((l, i) => {
                    balance += sign * (l.debit - l.credit);
                    return (
                      <Table.Tr
                        key={`${l.id}/${i}`}
                        className={`clickable-row ${selectedId === l.id ? "selected" : ""}`}
                        onClick={() => onOpen(l.id)}
                      >
                        <Table.Td>{l.date}</Table.Td>
                        <Table.Td>
                          <button
                            type="button"
                            className="doc-link"
                            aria-label={`${l.id} 전표 보기`}
                            onClick={(event) => {
                              event.stopPropagation();
                              onOpen(l.id);
                            }}
                          >
                            {l.id}
                          </button>
                        </Table.Td>
                        <Table.Td>{l.title}</Table.Td>
                        <Table.Td data-align="end">{amount(l.debit)}</Table.Td>
                        <Table.Td data-align="end">{amount(l.credit)}</Table.Td>
                        <Table.Td
                          data-align="end"
                          data-negative={balance < 0 || undefined}
                        >
                          {signed(balance)}
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                  <Table.Tr className="subtotal-row">
                    <Table.Td />
                    <Table.Td />
                    <Table.Td>기간 합계 · 기말잔액</Table.Td>
                    <Table.Td data-align="end">
                      {debit.toLocaleString("ko-KR")}
                    </Table.Td>
                    <Table.Td data-align="end">
                      {credit.toLocaleString("ko-KR")}
                    </Table.Td>
                    <Table.Td
                      data-align="end"
                      data-negative={balance < 0 || undefined}
                    >
                      {signed(balance)}
                    </Table.Td>
                  </Table.Tr>
                </Fragment>
              );
            })}
          </Table.Tbody>
        </Table>
        {!accounts.length && (
          <div className="empty-state">조회 기간에 전기된 전표가 없습니다.</div>
        )}
      </div>
      <div className="table-footer">
        <span>
          계정 <b>{accounts.length}</b>개
          <span className="muted">
            {" "}
            · 전기이월 = 조회 시작일 이전 전기 전표 누계
          </span>
        </span>
        <span className="muted">단위 원 · 미전기 전표 제외</span>
      </div>
    </div>
  );
}
