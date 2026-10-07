import {
  Table,
  Pagination,
  Skeleton,
  Alert,
  Button,
  Progress,
} from "@mantine/core";
import { IconArrowsSort } from "@tabler/icons-react";
import type { Column, RecordData } from "../domain/types";
import { fieldValue } from "../domain/helpers";
import { Money } from "./Money";
import { StatusBadge } from "./StatusBadge";
import { EmptyState } from "./EmptyState";
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
  size = 10,
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
}) {
  if (error)
    return (
      <Alert color="red" title="데이터를 불러오지 못했습니다">
        {error.message}
        <Button variant="light" color="red" ml="md" onClick={onRetry}>
          다시 시도
        </Button>
      </Alert>
    );
  return (
    <div className="data-table">
      <div className="table-scroll">
        <Table miw={760}>
          <Table.Thead>
            <Table.Tr>
              {columns.map((c) => (
                <Table.Th
                  key={c.key}
                  style={{
                    width: c.width,
                    textAlign: "center",
                  }}
                  aria-sort={
                    sort === c.key
                      ? direction === "asc"
                        ? "ascending"
                        : "descending"
                      : undefined
                  }
                >
                  <button
                    className="sort-button"
                    onClick={() => onSort?.(c.key)}
                  >
                    {c.label}
                    {onSort && <IconArrowsSort size={12} />}
                  </button>
                </Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {loading
              ? Array.from({ length: 6 }, (_, i) => (
                  <Table.Tr key={i}>
                    {columns.map((c) => (
                      <Table.Td key={c.key}>
                        <Skeleton height={17} />
                      </Table.Td>
                    ))}
                  </Table.Tr>
                ))
              : rows.map((r) => (
                  <Table.Tr
                    key={r.id}
                    onClick={() => onRow(r)}
                    tabIndex={0}
                    aria-label={`${r.id} 상세 보기`}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        onRow(r);
                      }
                    }}
                    className="clickable-row"
                  >
                    {columns.map((c, i) => (
                      <Table.Td
                        key={c.key}
                        style={{
                          textAlign: "center",
                        }}
                        className={i === 0 ? "document-number" : undefined}
                      >
                        {c.type === "status" ? (
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
      </div>
      {!loading && !rows.length && <EmptyState onReset={onReset} />}
      <div className="table-footer">
        <span>
          전체 <b>{total.toLocaleString()}</b>건{" "}
          <span className="muted">
            ·{" "}
            {total
              ? `${(page - 1) * size + 1}–${Math.min(page * size, total)}`
              : "0"}{" "}
            표시
          </span>
        </span>
        <Pagination
          total={Math.max(1, Math.ceil(total / size))}
          value={page}
          onChange={onPage}
          size="sm"
          siblings={1}
        />
      </div>
    </div>
  );
}
