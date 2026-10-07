import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  Table,
  Button,
  TextInput,
  Select,
  Alert,
  Loader,
} from "@mantine/core";
import {
  IconDownload,
  IconScale,
  IconBook2,
  IconArrowUpRight,
} from "@tabler/icons-react";
import { useAccounting } from "./api";
import { accountingModule } from "./config";
import { resourceByKey } from "../../app/catalog";
import { won } from "../../domain/helpers";
import { PageHeader } from "../../components/PageHeader";
import { KpiCard } from "../../components/KpiCard";
import { StatusBadge } from "../../components/StatusBadge";
import { DocumentDetails } from "../../components/DocumentDetails";
import { DocumentForm } from "../../components/DocumentForm";
import type { RecordData } from "../../domain/types";
import { exportCsv } from "../../domain/export";
export function FinancialReport() {
  const { screen } = useParams();
  const [from, setFrom] = useState("2026-01-01");
  const [to, setTo] = useState("2026-09-30");
  const [account, setAccount] = useState<string | null>(
    new URLSearchParams(window.location.search).get("account"),
  );
  const [search, setSearch] = useSearchParams();
  const [edit, setEdit] = useState<RecordData | null>(null);
  const query = useAccounting(
    screen === "balance-sheet" ? "2026-01-01" : from,
    to,
  );
  const title =
    accountingModule.items.find((i) => i.key === screen)?.label ??
    "회계 보고서";
  const data = query.data;
  const balances = data?.balances ?? [];
  const debit = balances.reduce((n, l) => n + l.debit, 0),
    credit = balances.reduce((n, l) => n + l.credit, 0);
  const net = (prefix: string) =>
    balances
      .filter((l) => l.account.startsWith(prefix))
      .reduce((n, l) => n + l.credit - l.debit, 0);
  const revenue = net("4"),
    cost = -net("5") - net("6"),
    income = revenue - cost;
  const assets = -net("1"),
    liabilities = net("2"),
    equity = net("3") + income;
  const reports =
    screen === "income"
      ? [
          ["매출액", revenue],
          ["매출원가", -net("5")],
          ["매출총이익", revenue + net("5")],
          ["판매비와관리비", -net("6")],
          ["당기순이익", income],
        ]
      : screen === "balance-sheet"
        ? [
            ["자산총계", assets],
            ["부채총계", liabilities],
            ["자본총계 (당기손익 포함)", equity],
            ["부채와 자본총계", liabilities + equity],
          ]
        : [];
  const ledgerRows = (data?.journals ?? []).flatMap((r) =>
    r.journal
      .filter((l) => !account || l.account === account)
      .map((l, i) => ({
        ...r,
        id: `${r.id}/${i}`,
        values: {
          ...r.values,
          entry: r.id,
          account: l.account,
          debit: l.debit,
          credit: l.credit,
        },
      })),
  );
  const resource = resourceByKey("journals")!;
  return (
    <>
      <PageHeader
        title={title}
        description="전기된 전표를 기준으로 집계한 회계 보고서입니다. 계정에서 원천 전표까지 확인할 수 있습니다."
        actions={
          <Button
            variant="default"
            leftSection={<IconDownload size={15} />}
            onClick={() => {
              if (screen === "ledger")
                exportCsv(
                  ledgerRows,
                  [
                    { key: "entry", label: "전표번호" },
                    { key: "date", label: "일자" },
                    { key: "account", label: "계정과목" },
                    { key: "debit", label: "차변" },
                    { key: "credit", label: "대변" },
                  ],
                  `${title}.csv`,
                );
              else {
                const rows = (
                  reports.length
                    ? reports.map(([account, value]) => ({
                        account,
                        debit: value,
                        credit: "",
                      }))
                    : balances
                ).map(
                  (b, i) =>
                    ({ id: String(i), values: b }) as unknown as RecordData,
                );
                exportCsv(
                  rows,
                  [
                    { key: "account", label: "계정과목" },
                    { key: "debit", label: reports.length ? "금액" : "차변" },
                    { key: "credit", label: "대변" },
                  ],
                  `${title}.csv`,
                );
              }
            }}
          >
            내보내기
          </Button>
        }
      />
      <div className="report-toolbar">
        {screen !== "balance-sheet" && (
          <TextInput
            label="조회 시작일"
            aria-label="보고서 시작일"
            type="date"
            value={from}
            onChange={(e) => setFrom(e.currentTarget.value)}
          />
        )}
        <TextInput
          label={screen === "balance-sheet" ? "기준일" : "조회 종료일"}
          aria-label="보고서 종료일"
          type="date"
          value={to}
          onChange={(e) => setTo(e.currentTarget.value)}
        />
        {screen === "ledger" && (
          <Select
            label="계정과목"
            placeholder="전체 계정"
            searchable
            clearable
            data={balances.map((l) => l.account)}
            value={account}
            onChange={setAccount}
          />
        )}
        <span>단위: 원(KRW) · 미전기 전표 제외</span>
      </div>
      <div className="kpi-grid">
        <KpiCard
          label="전기된 전표"
          value={
            <>
              {data?.journals.length ?? 0}
              <small>건</small>
            </>
          }
          detail="역분개 포함"
          icon={<IconBook2 size={19} />}
        />
        <KpiCard
          label="차변 합계"
          value={<span className="report-kpi-money">{won(debit)}</span>}
          detail="조회기간 누계"
        />
        <KpiCard
          label="대변 합계"
          value={<span className="report-kpi-money">{won(credit)}</span>}
          detail="조회기간 누계"
        />
        <KpiCard
          label="차대변 대사"
          value={debit === credit ? "일치" : "불일치"}
          detail={`차이 ${won(debit - credit)}`}
          icon={<IconScale size={20} />}
        />
      </div>
      {query.isLoading ? (
        <Loader />
      ) : query.error ? (
        <Alert color="red">
          {query.error.message}
          <Button onClick={() => query.refetch()}>다시 시도</Button>
        </Alert>
      ) : (
        <section className={`panel ${screen === "income" ? "income-statement-panel" : ""}`}>
          <div className="panel-heading">
            <div>
              <h2>한결 인더스트리 · {title}</h2>
              <p>
                {screen === "balance-sheet" ? `${to} 현재` : `${from} ~ ${to}`}
              </p>
            </div>
            <StatusBadge status={debit === credit ? "정상" : "부족"} />
          </div>
          {screen === "ledger" ? (
            <div className="table-scroll">
              <Table miw={750}>
                <Table.Thead>
                  <Table.Tr>
                    {[
                      "전표일",
                      "전표번호",
                      "계정과목",
                      "적요",
                      "차변",
                      "대변",
                    ].map((s) => (
                      <Table.Th key={s}>{s}</Table.Th>
                    ))}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {ledgerRows.map((r) => (
                    <Table.Tr
                      key={r.id}
                      className="clickable-row"
                      onClick={() => setSearch({ doc: String(r.values.entry) })}
                    >
                      <Table.Td>{r.date}</Table.Td>
                      <Table.Td className="document-number">
                        {r.values.entry}
                      </Table.Td>
                      <Table.Td>{r.values.account}</Table.Td>
                      <Table.Td>{r.title}</Table.Td>
                      <Table.Td ta="right">
                        {won(Number(r.values.debit))}
                      </Table.Td>
                      <Table.Td ta="right">
                        {won(Number(r.values.credit))}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </div>
          ) : reports.length ? (
            <div className="statement">
              <div className="statement-head">
                <span>구분</span>
                <span>금액</span>
              </div>
              {reports.map(([label, value], i) => (
                <div
                  className={i === reports.length - 1 ? "statement-total" : ""}
                  key={label}
                >
                  <span>{label}</span>
                  <strong>{won(Number(value))}</strong>
                </div>
              ))}
              {screen === "balance-sheet" && (
                <p className="statement-note">
                  자산 − (부채 + 자본) = {won(assets - liabilities - equity)}
                </p>
              )}
            </div>
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>계정과목</Table.Th>
                  <Table.Th ta="right">차변 합계</Table.Th>
                  <Table.Th ta="right">대변 합계</Table.Th>
                  <Table.Th ta="right">잔액 (차변 − 대변)</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {balances.map((l) => (
                  <Table.Tr key={l.account}>
                    <Table.Td>
                      <Link
                        to={`/accounting/ledger?account=${encodeURIComponent(l.account)}`}
                        onClick={() => setAccount(l.account)}
                        className="text-link"
                      >
                        {l.account}
                      </Link>
                    </Table.Td>
                    <Table.Td ta="right">{won(l.debit)}</Table.Td>
                    <Table.Td ta="right">{won(l.credit)}</Table.Td>
                    <Table.Td ta="right">{won(l.debit - l.credit)}</Table.Td>
                    <Table.Td>
                      <IconArrowUpRight size={13} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
              <Table.Tfoot>
                <Table.Tr>
                  <Table.Th>합계</Table.Th>
                  <Table.Th ta="right">{won(debit)}</Table.Th>
                  <Table.Th ta="right">{won(credit)}</Table.Th>
                  <Table.Th ta="right">{won(debit - credit)}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Tfoot>
            </Table>
          )}
        </section>
      )}
      {search.get("doc") && (
        <DocumentDetails
          resource={resource}
          id={search.get("doc")!}
          onClose={() => setSearch({})}
          onEdit={setEdit}
        />
      )}{" "}
      {edit && (
        <DocumentForm
          resource={resource}
          record={edit}
          onClose={() => setEdit(null)}
          onSaved={() => setEdit(null)}
        />
      )}
    </>
  );
}
