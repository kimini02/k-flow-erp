import { ResourceKpis } from "../components/ResourceKpis";
import { ProductionOverview } from "../features/manufacturing/ProductionOverview";
import { useState } from "react";
import { useParams, useSearchParams, Link } from "react-router-dom";
import { Button, Alert, SegmentedControl, Skeleton } from "@mantine/core";
import { IconPlus, IconDownload } from "@tabler/icons-react";
import { modules, resourceByKey } from "../app/catalog";
import { useRecords } from "../api/hooks";
import { api } from "../api/client";
import { useSession } from "../app/AppLayout";
import type { ListParams, RecordData } from "../domain/types";
import { fieldValue } from "../domain/helpers";
import { exportCsv } from "../domain/export";
import { PageHeader } from "../components/PageHeader";
import { DataTable } from "../components/DataTable";
import { FilterBar } from "../components/FilterBar";
import { DocumentDetails } from "../components/DocumentDetails";
import { DocumentForm } from "../components/DocumentForm";
import { ReportingPage } from "../features/reporting/ReportingPage";
import { FinancialReport } from "../features/accounting/FinancialReport";
import { notifications } from "@mantine/notifications";
import { StockOverview } from "../features/inventory/StockOverview";
import { AgingByPartner } from "../components/AgingByPartner";
import { ApprovalQueue } from "../features/approval/ApprovalQueue";
// Screens whose default view summarises documents instead of listing them.
const summaryViews: Record<string, [string, string]> = {
  stock: ["품목별 현황", "창고별 목록"],
  payables: ["거래처별 연령분석", "문서 목록"],
  receivables: ["거래처별 연령분석", "문서 목록"],
};
export function DocumentListPage() {
  const { module: moduleKey, screen } = useParams();
  const mod = modules.find((m) => m.key === moduleKey);
  const item = mod?.items.find((i) => i.key === screen);
  const resource = resourceByKey(item?.resource ?? "");
  if (moduleKey === "reporting") return <ReportingPage key={screen} />;
  if (
    moduleKey === "accounting" &&
    ["ledger", "trial-balance", "income", "balance-sheet"].includes(
      screen ?? "",
    )
  )
    return <FinancialReport key={screen} />;
  if (!mod || !item || !resource)
    return (
      <div className="empty-state">
        <h1>페이지를 찾을 수 없습니다</h1>
        <Link to="/">대시보드로 이동</Link>
      </div>
    );
  return (
    <ResourceList
      key={`${moduleKey}/${screen}`}
      resourceKey={resource.key}
      moduleKey={moduleKey!}
      screen={screen!}
    />
  );
}
export function ResourceList({
  resourceKey,
  moduleKey,
  screen,
}: {
  resourceKey: string;
  moduleKey: string;
  screen: string;
}) {
  const resource = resourceByKey(resourceKey)!;
  const mod = modules.find((m) => m.key === moduleKey)!;
  const item = mod.items.find((i) => i.key === screen)!;
  const { actor } = useSession();
  const [params, setParams] = useState<ListParams>({ page: 1, size: 20 });
  const [search, setSearch] = useSearchParams();
  const [form, setForm] = useState<RecordData | "new" | null>(
    search.get("new") === "1" ? "new" : null,
  );
  const effective = {
    ...params,
    status: item.status ?? params.status,
    owner: item.ownerOnly ? actor : undefined,
    participant:
      resource.kind === "approval" && screen === "inbox" ? actor : undefined,
  };
  const query = useRecords(resource.key, effective);
  const rows = query.data?.rows ?? [],
    all = query.data?.all ?? [];
  const reset = () => setParams({ page: 1, size: params.size });
  const open = (r: RecordData) => setSearch({ doc: r.id });
  const summary = summaryViews[resource.key];
  const [view, setView] = useState<"summary" | "list">(
    summary ? "summary" : "list",
  );
  const showSummary = !!summary && view === "summary";
  const fullQuery = useRecords(showSummary ? resource.key : "", {
    ...effective,
    page: 1,
    size: 100000,
  });
  const itemsQuery = useRecords(
    showSummary && resource.key === "stock" ? "items" : "",
    { size: 100000 },
  );
  const inbox = resource.key === "approvals" && screen === "inbox";
  async function download() {
    try {
      const result = await api.list(resource.key, {
        ...effective,
        page: 1,
        size: 100000,
      });
      exportCsv(
        result.rows,
        resource.columns,
        `${resource.label}-20260929.csv`,
      );
    } catch (e) {
      notifications.show({ message: (e as Error).message, color: "red" });
    }
  }
  return (
    <>
      <PageHeader
        title={item.label}
        description={resource.description}
        actions={
          <>
            <Button
              variant="default"
              leftSection={<IconDownload size={15} />}
              onClick={download}
            >
              내보내기
            </Button>
            {!resource.readOnly && (
              <Button
                leftSection={<IconPlus size={16} />}
                onClick={() => setForm("new")}
              >
                신규 {resource.singular}
              </Button>
            )}
          </>
        }
      />
      <ResourceKpis resource={resource} rows={all} loading={query.isLoading} />
      {inbox && (
        <ApprovalQueue
          rows={all}
          actor={actor}
          loading={query.isLoading}
          onOpen={open}
          selectedId={search.get("doc")}
        />
      )}
      {resource.key === "approvals" && !inbox && (
        <div className="context-banner">
          <span className="online-dot" />
          <span>
            <b>{actor}</b>님으로 조회 중입니다. 결재는 현재 순서의 결재자만
            처리할 수 있습니다.
          </span>
          <span>순차 결재</span>
        </div>
      )}
      {moduleKey === "manufacturing" &&
        ["overview", "progress"].includes(screen) && (
          <ProductionOverview rows={all} onOpen={open} />
        )}
      <div className="panel list-panel">
        <div className="list-panel-heading">
          <h2>
            {summary ? item.label : `${item.label} 목록`}{" "}
            <span>{query.data?.total ?? 0}</span>
          </h2>
          {summary ? (
            <SegmentedControl
              size="xs"
              aria-label="보기 방식"
              data={[
                { value: "summary", label: summary[0] },
                { value: "list", label: summary[1] },
              ]}
              value={view}
              onChange={(v) => setView(v as "summary" | "list")}
            />
          ) : (
            <span>2026년 데모 데이터</span>
          )}
        </div>
        <FilterBar
          params={params}
          onChange={setParams}
          statuses={resource.statuses}
          entities={all.map((r) =>
            String(fieldValue(r, resource.filters?.key ?? "partner")),
          )}
          entityLabel={resource.filters?.label ?? "거래처"}
          fixedStatus={!!item.status}
        />
        {params.from && params.to && params.from > params.to && (
          <Alert color="orange">시작일은 종료일 이전으로 설정해 주세요.</Alert>
        )}
        {showSummary ? (
          fullQuery.error || itemsQuery.error ? (
            <Alert color="red" title="데이터를 불러오지 못했습니다" m="md">
              {(fullQuery.error ?? itemsQuery.error)?.message}
              <Button
                variant="light"
                color="red"
                ml="md"
                onClick={() => {
                  void query.refetch();
                  void fullQuery.refetch();
                  void itemsQuery.refetch();
                }}
              >
                다시 시도
              </Button>
            </Alert>
          ) : fullQuery.isLoading || itemsQuery.isLoading ? (
            <div className="summary-loading">
              <Skeleton height={14} />
              <Skeleton height={14} />
              <Skeleton height={14} />
            </div>
          ) : resource.key === "stock" ? (
            <StockOverview
              rows={fullQuery.data?.rows ?? []}
              items={itemsQuery.data?.rows ?? []}
              onOpen={open}
              selectedId={search.get("doc")}
              onReset={reset}
            />
          ) : (
            <AgingByPartner
              rows={fullQuery.data?.rows ?? []}
              kind={resource.key as "payables" | "receivables"}
              onReset={reset}
              onPartner={(partner) => {
                setParams({ ...params, entity: partner, page: 1 });
                setView("list");
              }}
            />
          )
        ) : (
          <DataTable
            columns={resource.columns}
            rows={rows}
            total={query.data?.total ?? 0}
            page={params.page ?? 1}
            onPage={(p) => setParams({ ...params, page: p })}
            size={params.size ?? 20}
            onSize={(size) => setParams({ ...params, size, page: 1 })}
            selectedId={search.get("doc")}
            onRow={open}
            onSort={(sort) =>
              setParams({
                ...params,
                sort,
                direction:
                  params.sort === sort && params.direction === "asc"
                    ? "desc"
                    : "asc",
                page: 1,
              })
            }
            sort={params.sort}
            direction={params.direction}
            loading={query.isLoading}
            error={query.error}
            onRetry={() => query.refetch()}
            onReset={reset}
          />
        )}
      </div>
      {search.get("doc") && (
        <DocumentDetails
          resource={resource}
          id={search.get("doc")!}
          onClose={() => setSearch({})}
          onEdit={(r) => setForm(r)}
        />
      )}{" "}
      {form && (
        <DocumentForm
          resource={resource}
          record={form === "new" ? undefined : form}
          onClose={() => setForm(null)}
          onSaved={(r) => {
            setForm(null);
            open(r);
          }}
        />
      )}
    </>
  );
}
