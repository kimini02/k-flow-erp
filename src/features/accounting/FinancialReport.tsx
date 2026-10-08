import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { Button, TextInput, Select, Alert, Loader } from "@mantine/core";
import { IconDownload } from "@tabler/icons-react";
import { useAccounting } from "./api";
import { accountingModule } from "./config";
import { resourceByKey } from "../../app/catalog";
import { PageHeader } from "../../components/PageHeader";
import { DocumentDetails } from "../../components/DocumentDetails";
import { DocumentForm } from "../../components/DocumentForm";
import type { RecordData } from "../../domain/types";
import { exportCsv } from "../../domain/export";
import { GeneralLedger } from "./GeneralLedger";
import {
  StatementFrame,
  StatementTable,
  TrialBalanceTable,
  balanceSheet,
  incomeStatement,
} from "./Statements";
const dayBefore = (date: string) => {
  const d = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return "";
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
};
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
  // Opening balances for the general ledger: everything posted before the start date.
  const openingQuery = useAccounting("", dayBefore(from) || "0000-00-00");
  const title =
    accountingModule.items.find((i) => i.key === screen)?.label ??
    "회계 보고서";
  const data = query.data;
  const balances = data?.balances ?? [];
  const debit = balances.reduce((n, l) => n + l.debit, 0),
    credit = balances.reduce((n, l) => n + l.credit, 0);
  const income = incomeStatement(balances);
  const sheet = balanceSheet(balances);
  const reports =
    screen === "income"
      ? income.lines
      : screen === "balance-sheet"
        ? sheet.lines
        : [];
  const period =
    screen === "balance-sheet" ? `${to} 현재` : `${from} 부터 ${to} 까지`;
  const balanced = debit === credit;
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
                    ? reports.map((l) => ({
                        account: `${l.roman ? `${l.roman}. ` : ""}${l.label}`,
                        debit: l.detail ?? "",
                        credit: l.value ?? "",
                      }))
                    : balances
                ).map(
                  (b, i) =>
                    ({ id: String(i), values: b }) as unknown as RecordData,
                );
                exportCsv(
                  rows,
                  [
                    {
                      key: "account",
                      label: reports.length ? "과목" : "계정과목",
                    },
                    {
                      key: "debit",
                      label: reports.length ? "계정 금액" : "차변 합계",
                    },
                    {
                      key: "credit",
                      label: reports.length ? "구분 금액" : "대변 합계",
                    },
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
        <span className="report-check">
          전기 전표 {data?.journals.length ?? 0}건 · 차변{" "}
          {debit.toLocaleString("ko-KR")} · 대변{" "}
          {credit.toLocaleString("ko-KR")}{" "}
          <b data-ok={balanced || undefined}>
            {balanced ? "✓ 차대변 일치" : "차대변 불일치"}
          </b>
        </span>
      </div>
      {query.isLoading ? (
        <Loader />
      ) : query.error ? (
        <Alert color="red">
          {query.error.message}
          <Button onClick={() => query.refetch()}>다시 시도</Button>
        </Alert>
      ) : (
        <>
          {screen === "ledger" ? (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>한결 인더스트리 · {title}</h2>
                  <p>
                    {from} ~ {to} · 단위 원 · 미전기 전표 제외
                  </p>
                </div>
              </div>
              <GeneralLedger
                journals={data?.journals ?? []}
                opening={openingQuery.data?.balances ?? []}
                account={account}
                from={from}
                selectedId={search.get("doc")}
                onOpen={(id) => setSearch({ doc: id })}
              />
            </section>
          ) : screen === "trial-balance" ? (
            <StatementFrame
              title="합계잔액시산표"
              period={period}
              check={{
                ok: balanced,
                label: balanced
                  ? "차변 합계 = 대변 합계"
                  : `차변 − 대변 = ${(debit - credit).toLocaleString("ko-KR")}`,
              }}
              note="전기된 전표 기준 · 계정과목을 누르면 총계정원장으로 이동"
            >
              <TrialBalanceTable balances={balances} />
            </StatementFrame>
          ) : screen === "income" ? (
            <StatementFrame
              title="손 익 계 산 서"
              period={period}
              note="영업외손익·법인세비용 계정이 없어 영업이익과 당기순이익이 같습니다 (데모 계정과목 범위) · 괄호는 음수"
            >
              <StatementTable lines={reports} />
            </StatementFrame>
          ) : (
            <StatementFrame
              title="재 무 상 태 표"
              period={period}
              check={{
                ok: sheet.assetTotal === sheet.claimTotal,
                label:
                  sheet.assetTotal === sheet.claimTotal
                    ? "자산총계 = 부채와자본총계"
                    : `자산 − (부채 + 자본) = ${(sheet.assetTotal - sheet.claimTotal).toLocaleString("ko-KR")}`,
              }}
              note="2026년 1월 1일부터 기준일까지 전기된 전표 누계 · 당기순이익은 이익잉여금에 포함 · 괄호는 음수"
            >
              <StatementTable lines={reports} />
            </StatementFrame>
          )}
        </>
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
