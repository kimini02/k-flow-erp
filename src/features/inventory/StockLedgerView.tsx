import { Fragment, useMemo, useState } from "react";
import { Button, Select, Table, TextInput } from "@mantine/core";
import { IconRefresh } from "@tabler/icons-react";
import type { RecordData } from "../../domain/types";

const qty = (n: number) => (n ? n.toLocaleString("ko-KR") : "—");
const DEFAULT_RANGE = { from: "2026-09-01", to: "2026-09-30" };

// 수불부: per item x warehouse, carried balance + movements in the period + closing,
// checked against current stock (INV-01: ledger total equals current stock).
export function StockLedgerView({
  ledger,
  stock,
  items,
  onOpen,
  selectedId,
}: {
  ledger: RecordData[];
  stock: RecordData[];
  items: RecordData[];
  onOpen: (r: RecordData) => void;
  selectedId?: string | null;
}) {
  const [item, setItem] = useState<string | null>(null);
  const [warehouse, setWarehouse] = useState<string | null>(null);
  const [range, setRange] = useState(DEFAULT_RANGE);
  const itemOrder = items.map((i) => i.title);
  const groups = useMemo(() => {
    const map = new Map<string, RecordData[]>();
    for (const r of ledger) {
      const key = `${r.title}|${r.values.warehouse}`;
      map.set(key, [...(map.get(key) ?? []), r]);
    }
    return [...map.entries()]
      .map(([key, rows]) => {
        const [title, wh] = key.split("|");
        return {
          item: title,
          warehouse: wh,
          rows: rows.sort(
            (a, b) => a.date.localeCompare(b.date) || a.id.localeCompare(b.id),
          ),
        };
      })
      .filter(
        (g) =>
          (!item || g.item === item) &&
          (!warehouse || g.warehouse === warehouse),
      )
      .sort(
        (a, b) =>
          (itemOrder.indexOf(a.item) + 1 || 99) -
            (itemOrder.indexOf(b.item) + 1 || 99) ||
          a.warehouse.localeCompare(b.warehouse, "ko"),
      );
  }, [ledger, item, warehouse, itemOrder.join()]);
  const delta = (r: RecordData) =>
    Number(r.values.inQty ?? 0) - Number(r.values.outQty ?? 0);
  const sections = groups
    .map((g) => {
      const carry = g.rows
        .filter((r) => r.date < range.from)
        .reduce((n, r) => n + delta(r), 0);
      const period = g.rows.filter(
        (r) => r.date >= range.from && r.date <= range.to,
      );
      const later = g.rows.filter((r) => r.date > range.to).length;
      const current = stock.find(
        (s) => s.title === g.item && s.values.warehouse === g.warehouse,
      );
      return { ...g, carry, period, later, current };
    })
    .filter((s) => s.carry || s.period.length);
  const meta = (title: string) => items.find((i) => i.title === title);
  const unit = (title: string) => String(meta(title)?.values.unit ?? "EA");
  const options = (values: string[]) => [...new Set(values)].filter(Boolean);
  return (
    <>
      <div className="filter-bar" role="group" aria-label="수불부 조회 조건">
        <Select
          className="filter-select wide"
          aria-label="품목"
          placeholder="전체 품목"
          searchable
          clearable
          data={options([...itemOrder, ...ledger.map((r) => r.title)])}
          value={item}
          onChange={setItem}
        />
        <Select
          className="filter-select"
          aria-label="창고"
          placeholder="전체 창고"
          clearable
          data={options(ledger.map((r) => String(r.values.warehouse)))}
          value={warehouse}
          onChange={setWarehouse}
        />
        <div className="date-range">
          <TextInput
            type="date"
            aria-label="수불 조회 시작일"
            value={range.from}
            onChange={(e) =>
              setRange({ ...range, from: e.currentTarget.value })
            }
          />
          <span aria-hidden="true">~</span>
          <TextInput
            type="date"
            aria-label="수불 조회 종료일"
            min={range.from}
            value={range.to}
            onChange={(e) => setRange({ ...range, to: e.currentTarget.value })}
          />
        </div>
        <Button
          variant="default"
          aria-label="조회 조건 초기화"
          leftSection={<IconRefresh size={14} />}
          onClick={() => {
            setItem(null);
            setWarehouse(null);
            setRange(DEFAULT_RANGE);
          }}
        >
          초기화
        </Button>
      </div>
      <div className="data-table">
        <div className="table-scroll">
          <Table miw={860} highlightOnHover={false}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th style={{ width: 112 }}>일자</Table.Th>
                <Table.Th style={{ width: 140 }}>수불번호</Table.Th>
                <Table.Th style={{ width: 110 }}>수불유형</Table.Th>
                <Table.Th>원천문서</Table.Th>
                <Table.Th data-align="end" style={{ width: 110 }}>
                  입고
                </Table.Th>
                <Table.Th data-align="end" style={{ width: 110 }}>
                  출고
                </Table.Th>
                <Table.Th data-align="end" style={{ width: 120 }}>
                  잔량
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {sections.map((s) => {
                let balance = s.carry;
                const received = s.period.reduce(
                  (n, r) => n + Number(r.values.inQty ?? 0),
                  0,
                );
                const issued = s.period.reduce(
                  (n, r) => n + Number(r.values.outQty ?? 0),
                  0,
                );
                const closing = s.carry + received - issued;
                const currentQty = s.current
                  ? Number(s.current.values.qty)
                  : null;
                return (
                  <Fragment key={`${s.item}|${s.warehouse}`}>
                    <Table.Tr className="account-row">
                      <Table.Td colSpan={7}>
                        <b>{s.item}</b>
                        <small>
                          {s.warehouse} ·{" "}
                          {String(meta(s.item)?.values.category ?? "")} · 단위{" "}
                          {unit(s.item)}
                        </small>
                      </Table.Td>
                    </Table.Tr>
                    <Table.Tr className="carry-row">
                      <Table.Td>{range.from}</Table.Td>
                      <Table.Td />
                      <Table.Td>전기이월</Table.Td>
                      <Table.Td />
                      <Table.Td data-align="end" />
                      <Table.Td data-align="end" />
                      <Table.Td data-align="end">
                        {s.carry.toLocaleString("ko-KR")}
                      </Table.Td>
                    </Table.Tr>
                    {s.period.map((r) => {
                      balance += delta(r);
                      return (
                        <Table.Tr
                          key={r.id}
                          className={`clickable-row ${selectedId === r.id ? "selected" : ""}`}
                          aria-selected={selectedId === r.id || undefined}
                          onClick={() => onOpen(r)}
                        >
                          <Table.Td>{r.date}</Table.Td>
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
                              {r.id}
                            </button>
                          </Table.Td>
                          <Table.Td>
                            <span
                              className="movement"
                              data-direction={delta(r) >= 0 ? "in" : "out"}
                            >
                              {String(r.values.movement)}
                            </span>
                          </Table.Td>
                          <Table.Td
                            className={r.values.reference ? undefined : "muted"}
                          >
                            {String(r.values.reference || "—")}
                          </Table.Td>
                          <Table.Td data-align="end">
                            {qty(Number(r.values.inQty ?? 0))}
                          </Table.Td>
                          <Table.Td data-align="end">
                            {qty(Number(r.values.outQty ?? 0))}
                          </Table.Td>
                          <Table.Td data-align="end">
                            {balance.toLocaleString("ko-KR")}
                          </Table.Td>
                        </Table.Tr>
                      );
                    })}
                    <Table.Tr className="subtotal-row">
                      <Table.Td />
                      <Table.Td />
                      <Table.Td>기간 합계</Table.Td>
                      <Table.Td>
                        {s.later ? (
                          <span className="ledger-check muted">
                            {range.to} 이후 수불 {s.later}건
                          </span>
                        ) : currentQty === null ? null : currentQty ===
                          closing ? (
                          <span className="ledger-check" data-ok>
                            현재고 {currentQty.toLocaleString("ko-KR")}과 일치
                          </span>
                        ) : (
                          <span className="ledger-check">
                            현재고 {currentQty.toLocaleString("ko-KR")} · 차이{" "}
                            {(closing - currentQty).toLocaleString("ko-KR")}
                          </span>
                        )}
                      </Table.Td>
                      <Table.Td data-align="end">
                        {received.toLocaleString("ko-KR")}
                      </Table.Td>
                      <Table.Td data-align="end">
                        {issued.toLocaleString("ko-KR")}
                      </Table.Td>
                      <Table.Td data-align="end">
                        {closing.toLocaleString("ko-KR")}
                      </Table.Td>
                    </Table.Tr>
                  </Fragment>
                );
              })}
            </Table.Tbody>
          </Table>
          {!sections.length && (
            <div className="empty-state">
              조회 조건에 해당하는 수불이 없습니다.
            </div>
          )}
        </div>
        <div className="table-footer">
          <span>
            품목·창고 <b>{sections.length}</b>개
            <span className="muted">
              {" "}
              · 전기이월 = 조회 시작일 이전 수불 누계
            </span>
          </span>
          <span className="muted">
            기초재고는 Mock 역산값 · 기초재고 정책 DR-10/20 미정
          </span>
        </div>
      </div>
    </>
  );
}
