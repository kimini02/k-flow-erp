import {
  Table,
  Pagination,
  Skeleton,
  Alert,
  Button,
  Progress,
  Select,
} from "@mantine/core";
import {
  IconArrowsSort,
  IconArrowUp,
  IconArrowDown,
} from "@tabler/icons-react";
import type { Column, RecordData } from "../domain/types";
import { fieldValue } from "../domain/helpers";
import { Money } from "./Money";
import { StatusBadge } from "./StatusBadge";
import { EmptyState } from "./EmptyState";
export const PAGE_SIZES = [10, 20, 50, 100];
const numeric = (c: Column) => c.type === "money" || c.type === "number";
// Configs rarely set widths, so give identifiers, dates, amounts and states a stable column.
function columnWidth(c: Column, index: number) {
  if (c.width) return c.width;
  if (index === 0) return 150;
  if (c.type === "status") return 116;
  if (c.type === "money") return 140;
  if (c.type === "number" || c.type === "progress") return 112;
  if (/date|Date|time/.test(c.key)) return 112;
  return undefined;
}
export function DataTable({
  columns,
  rows,
  total,
  page,
  onPage,
  onRow,
  onSort,
  sort,
  direction,
  loading,
  error,
  onRetry,
  onReset,
  size = 20,
  onSize,
  selectedId,
}: {
  columns: Column[];
  rows: RecordData[];
  total: number;
  page: number;
  onPage: (n: number) => void;
  onRow: (r: RecordData) => void;
  onSort?: (key: string) => void;
  sort?: string;
  direction?: string;
  loading?: boolean;
  error?: Error | null;
  onRetry?: () => void;
  onReset?: () => void;
  size?: number;
  onSize?: (n: number) => void;
  selectedId?: string | null;
}) {
  if (error)
    return (
      <Alert color="red" title="데이터를 불러오지 못했습니다" m="md">
        {error.message}
        <Button variant="light" color="red" ml="md" onClick={onRetry}>
          다시 시도
        </Button>
      </Alert>
    );
  return (
    <div className="data-table">
      <div className="table-scroll">
        <Table miw={760} highlightOnHover={false}>
          <Table.Thead>
            <Table.Tr>
              {columns.map((c, i) => {
                const active = sort === c.key;
                const SortIcon = !active
                  ? IconArrowsSort
                  : direction === "asc"
                    ? IconArrowUp
                    : IconArrowDown;
                return (
                  <Table.Th
                    key={c.key}
                    style={{ width: columnWidth(c, i) }}
                    data-align={numeric(c) ? "end" : undefined}
                    aria-sort={
                      active
                        ? direction === "asc"
                          ? "ascending"
                          : "descending"
                        : undefined
                    }
                  >
                    {onSort ? (
                      <button
                        type="button"
                        className={`sort-button ${active ? "active" : ""}`}
                        onClick={() => onSort(c.key)}
                      >
                        {c.label}
                        <SortIcon size={12} aria-hidden="true" />
                      </button>
                    ) : (
                      c.label
                    )}
                  </Table.Th>
                );
              })}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {loading
              ? Array.from({ length: Math.min(size, 8) }, (_, i) => (
                  <Table.Tr key={i}>
                    {columns.map((c) => (
                      <Table.Td key={c.key}>
                        <Skeleton height={14} />
                      </Table.Td>
                    ))}
                  </Table.Tr>
                ))
              : rows.map((r) => (
                  <Table.Tr
                    key={r.id}
                    onClick={() => onRow(r)}
                    className={`clickable-row ${selectedId === r.id ? "selected" : ""}`}
                    aria-selected={selectedId === r.id || undefined}
                  >
                    {columns.map((c, i) => (
                      <Table.Td
                        key={c.key}
                        data-align={numeric(c) ? "end" : undefined}
                      >
                        {i === 0 ? (
                          <button
                            type="button"
                            className="doc-link"
                            aria-label={`${r.id} 상세 보기`}
                            onClick={(event) => {
                              event.stopPropagation();
                              onRow(r);
                            }}
                          >
                            {String(fieldValue(r, c.key)) || r.id}
                          </button>
                        ) : c.type === "status" ? (
                          <StatusBadge status={String(fieldValue(r, c.key))} />
                        ) : c.type === "money" ? (
                          <Money value={Number(fieldValue(r, c.key))} />
                        ) : c.type === "progress" ? (
                          <div className="progress-cell">
                            <Progress
                              value={Number(fieldValue(r, c.key))}
                              size={5}
                            />
                            <span>{fieldValue(r, c.key)}%</span>
                          </div>
                        ) : (
                          String(fieldValue(r, c.key)) || "—"
                        )}
                      </Table.Td>
                    ))}
                  </Table.Tr>
                ))}
          </Table.Tbody>
        </Table>
        {!loading && !rows.length && <EmptyState onReset={onReset} />}
      </div>
      <div className="table-footer">
        <span>
          전체 <b>{total.toLocaleString()}</b>건
          <span className="muted">
            {" · "}
            {total
              ? `${(page - 1) * size + 1}–${Math.min(page * size, total)}`
              : "0"}{" "}
            표시
          </span>
        </span>
        <div className="table-footer-controls">
          {onSize && (
            <Select
              aria-label="페이지당 행 수"
              className="page-size"
              size="xs"
              data={PAGE_SIZES.map((n) => ({
                value: String(n),
                label: `${n}개씩`,
              }))}
              value={String(size)}
              onChange={(v) => v && onSize(Number(v))}
              allowDeselect={false}
            />
          )}
          <Pagination
            total={Math.max(1, Math.ceil(total / size))}
            value={page}
            onChange={onPage}
            size="sm"
            siblings={1}
          />
        </div>
      </div>
    </div>
  );
}
