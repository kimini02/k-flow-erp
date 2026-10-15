import { Fragment, useMemo, useState } from "react";
import { Table } from "@mantine/core";
import { IconChevronRight } from "@tabler/icons-react";
import type { RecordData } from "../../domain/types";
import { StatusBadge } from "../../components/StatusBadge";
import { EmptyState } from "../../components/EmptyState";

type ItemGroup = {
  item: string;
  category: string;
  unit: string;
  rows: RecordData[];
  qty: number;
  reserved: number;
  available: number;
  safety: number;
  amount: number;
  short: number;
};
const num = (value: unknown) => Number(value ?? 0);
const qty = (n: number) => n.toLocaleString("ko-KR");

function Coverage({
  available,
  safety,
}: {
  available: number;
  safety: number;
}) {
  const pct = safety ? Math.round((available / safety) * 100) : 100;
  return (
    <div
      className="coverage"
      title={`가용 ${qty(available)} / 안전재고 ${qty(safety)}`}
    >
      <span>
        <i
          data-low={pct < 100 || undefined}
          style={{ width: `${Math.min(100, pct)}%` }}
        />
      </span>
      <em data-low={pct < 100 || undefined}>{pct}%</em>
    </div>
  );
}

// Item-first stock view: totals per item, warehouses underneath, shortages first and open.
export function StockOverview({
  rows,
  items,
  onOpen,
  selectedId,
  onReset,
}: {
  rows: RecordData[];
  items: RecordData[];
  onOpen: (r: RecordData) => void;
  selectedId?: string | null;
  onReset?: () => void;
}) {
  const groups = useMemo(() => {
    const map = new Map<string, ItemGroup>();
    for (const r of rows) {
      const meta = items.find((i) => i.title === r.title);
      const g = map.get(r.title) ?? {
        item: r.title,
        category: String(meta?.values.category ?? "—"),
        unit: String(meta?.values.unit ?? "EA"),
        rows: [],
        qty: 0,
        reserved: 0,
        available: 0,
        safety: 0,
        amount: 0,
        short: 0,
      };
      g.rows.push(r);
      g.qty += num(r.values.qty);
      g.reserved += num(r.values.reserved);
      g.available += num(r.values.available);
      g.safety += num(r.values.safety);
      g.amount += r.amount;
      if (r.status === "부족") g.short++;
      map.set(r.title, g);
    }
    for (const g of map.values())
      g.rows.sort((a, b) =>
        String(a.values.warehouse).localeCompare(
          String(b.values.warehouse),
          "ko",
        ),
      );
    return [...map.values()].sort(
      (a, b) => b.short - a.short || a.item.localeCompare(b.item, "ko"),
    );
  }, [rows, items]);
  const [toggled, setToggled] = useState<Record<string, boolean>>({});
  const isOpen = (g: ItemGroup) => toggled[g.item] ?? g.short > 0;
  if (!rows.length) return <EmptyState onReset={onReset} />;
  const total = groups.reduce(
    (t, g) => ({
      qty: t.qty + g.qty,
      reserved: t.reserved + g.reserved,
      available: t.available + g.available,
      safety: t.safety + g.safety,
      amount: t.amount + g.amount,
    }),
    { qty: 0, reserved: 0, available: 0, safety: 0, amount: 0 },
  );
  return (
    <div className="data-table">
      <div className="table-scroll">
        <Table miw={900} highlightOnHover={false}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th style={{ width: 240 }}>품목 / 창고</Table.Th>
              <Table.Th style={{ width: 80 }}>유형</Table.Th>
              <Table.Th style={{ width: 56 }}>단위</Table.Th>
              <Table.Th data-align="end">현재고</Table.Th>
              <Table.Th data-align="end">예약</Table.Th>
              <Table.Th data-align="end">가용재고</Table.Th>
              <Table.Th data-align="end">안전재고</Table.Th>
              <Table.Th style={{ width: 150 }}>안전재고 대비</Table.Th>
              <Table.Th data-align="end">평가액</Table.Th>
              <Table.Th style={{ width: 120 }}>상태</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {groups.map((g) => {
              const open = isOpen(g);
              return (
                <Fragment key={g.item}>
                  <Table.Tr
                    className="group-row clickable-row"
                    onClick={() =>
                      setToggled((t) => ({ ...t, [g.item]: !open }))
                    }
                  >
                    <Table.Td>
                      <div className="tree-cell">
                        <button
                          type="button"
                          className="tree-toggle"
                          aria-expanded={open}
                          aria-label={`${g.item} 창고별 재고 ${open ? "접기" : "펼치기"}`}
                          onClick={(event) => {
                            event.stopPropagation();
                            setToggled((t) => ({ ...t, [g.item]: !open }));
                          }}
                        >
                          <IconChevronRight size={14} aria-hidden="true" />
                        </button>
                        <b>{g.item}</b>
                        <small>창고 {g.rows.length}곳</small>
                      </div>
                    </Table.Td>
                    <Table.Td>{g.category}</Table.Td>
                    <Table.Td>{g.unit}</Table.Td>
                    <Table.Td data-align="end">{qty(g.qty)}</Table.Td>
                    <Table.Td data-align="end">{qty(g.reserved)}</Table.Td>
                    <Table.Td data-align="end">
                      <b>{qty(g.available)}</b>
                    </Table.Td>
                    <Table.Td data-align="end">{qty(g.safety)}</Table.Td>
                    <Table.Td>
                      <Coverage available={g.available} safety={g.safety} />
                    </Table.Td>
                    <Table.Td data-align="end">
                      {g.amount.toLocaleString("ko-KR")}
                    </Table.Td>
                    <Table.Td>
                      {g.short ? (
                        <span className="status-with-note">
                          <StatusBadge status="부족" />
                          <small>{g.short}곳</small>
                        </span>
                      ) : (
                        <StatusBadge status="정상" />
                      )}
                    </Table.Td>
                  </Table.Tr>
                  {open &&
                    g.rows.map((r) => (
                      <Table.Tr
                        key={r.id}
                        className={`child-row clickable-row ${selectedId === r.id ? "selected" : ""}`}
                        aria-selected={selectedId === r.id || undefined}
                        onClick={() => onOpen(r)}
                      >
                        <Table.Td>
                          <button
                            type="button"
                            className="doc-link"
                            aria-label={`${r.id} 상세 보기`}
                            onClick={(event) => {
                              event.stopPropagation();
                              onOpen(r);
                            }}
                          >
                            {String(r.values.warehouse)}
                          </button>
                        </Table.Td>
                        <Table.Td />
                        <Table.Td />
                        <Table.Td data-align="end">
                          {qty(num(r.values.qty))}
                        </Table.Td>
                        <Table.Td data-align="end">
                          {qty(num(r.values.reserved))}
                        </Table.Td>
                        <Table.Td data-align="end">
                          {qty(num(r.values.available))}
                        </Table.Td>
                        <Table.Td data-align="end">
                          {qty(num(r.values.safety))}
                        </Table.Td>
                        <Table.Td>
                          <Coverage
                            available={num(r.values.available)}
                            safety={num(r.values.safety)}
                          />
                        </Table.Td>
                        <Table.Td data-align="end">
                          {r.amount.toLocaleString("ko-KR")}
                        </Table.Td>
                        <Table.Td>
                          <StatusBadge status={r.status} />
                        </Table.Td>
                      </Table.Tr>
                    ))}
                </Fragment>
              );
            })}
          </Table.Tbody>
          <Table.Tfoot>
            <Table.Tr className="total-row">
              <Table.Td>
                합계{" "}
                <small>
                  품목 {groups.length}개 · 창고별 {rows.length}건
                </small>
              </Table.Td>
              <Table.Td />
              <Table.Td />
              <Table.Td data-align="end">{qty(total.qty)}</Table.Td>
              <Table.Td data-align="end">{qty(total.reserved)}</Table.Td>
              <Table.Td data-align="end">{qty(total.available)}</Table.Td>
              <Table.Td data-align="end">{qty(total.safety)}</Table.Td>
              <Table.Td />
              <Table.Td data-align="end">
                {total.amount.toLocaleString("ko-KR")}
              </Table.Td>
              <Table.Td />
            </Table.Tr>
          </Table.Tfoot>
        </Table>
      </div>
      <div className="table-footer">
        <span>
          품목 <b>{groups.length}</b>개 · 창고별 <b>{rows.length}</b>건
          <span className="muted"> · 수량 단위는 품목 기준, 금액 단위 원</span>
        </span>
        <span className="muted">부족 품목을 먼저 펼쳐 보여 줍니다</span>
      </div>
    </div>
  );
}
