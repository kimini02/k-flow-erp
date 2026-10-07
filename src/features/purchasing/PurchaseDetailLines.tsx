import { Table } from "@mantine/core";
import type { RecordData, Resource } from "../../domain/types";
import { won } from "../../domain/helpers";
import { useRecords } from "../../api/hooks";
import { purchaseTotals, receiptProgress } from "./model";
export function PurchaseDetailLines({
  resource,
  row,
}: {
  resource: Resource;
  row: RecordData;
}) {
  const key = resource.key,
    po = key === "purchase-orders",
    gr = key === "goods-receipts",
    bill = key === "purchase-bills",
    pr = key === "purchase-requests",
    pay = key === "payments-out";
  const receipts = useRecords(po || gr ? "goods-receipts" : "", {
    size: 10000,
  });
  const orders = useRecords(gr ? "purchase-orders" : "", { size: 10000 });
  const ap = useRecords(pay ? "payables" : "", { size: 10000 });
  const order = po
    ? row
    : orders.data?.all.find((x) => x.id === row.values.sourceId);
  const progress = order
    ? receiptProgress(order, receipts.data?.all || [], gr ? row.id : "")
    : [];
  if (key === "payables") return null;
  if (pay) {
    const allocations = JSON.parse(
      String(row.values.allocations || "{}"),
    ) as Record<string, number>;
    return (
      <section>
        <h3>지급 배분 내역</h3>
        <p className="muted">외부에서 완료된 지급 기록 · 은행 송금 실행 아님</p>
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>AP 번호</Table.Th>
              <Table.Th>원천 매입문서</Table.Th>
              <Table.Th ta="right">이번 지급액</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {Object.entries(allocations)
              .filter(([, v]) => v > 0)
              .map(([id, value]) => (
                <Table.Tr key={id}>
                  <Table.Td>{id}</Table.Td>
                  <Table.Td>
                    {String(
                      ap.data?.all.find((x) => x.id === id)?.values.billId ||
                        "—",
                    )}
                  </Table.Td>
                  <Table.Td ta="right">{won(value)}</Table.Td>
                </Table.Tr>
              ))}
          </Table.Tbody>
        </Table>
        <div className="detail-total">
          지급액 <strong>{won(row.amount)}</strong>
        </div>
      </section>
    );
  }
  const total = purchaseTotals(row);
  return (
    <section>
      <h3>{gr ? "입고 품목" : po ? "발주 품목 · 입고 진행" : "품목 내역"}</h3>
      {row.values.sourceId && (
        <p className="muted">원천문서 · {String(row.values.sourceId)}</p>
      )}
      <div className="purchase-grid-scroll">
        <Table className="purchase-edit-grid">
          <Table.Thead>
            <Table.Tr>
              {(po || gr
                ? [
                    "품목코드",
                    "품목명",
                    "단위",
                    po ? "발주수량" : "발주수량",
                    po ? "확정 입고" : "다른 확정 입고",
                    po ? "미입고 잔량" : "이번 입고",
                  ]
                : bill
                  ? ["품목", "수량", "공급가액", "세액", "합계"]
                  : ["품목", "규격", "단위", "요청수량", "예상단가", "예상금액"]
              ).map((h, i) => (
                <Table.Th key={h} ta={i > 2 ? "right" : undefined}>
                  {h}
                </Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {row.lines.map((l, i) => {
              const p = progress.find(
                (x) => x.id === (gr ? l.sourceLineId : l.id),
              );
              const net = Math.round(l.qty * l.price),
                tax =
                  row.values.taxType === "일반과세 10%"
                    ? Math.round(net * 0.1)
                    : 0;
              return (
                <Table.Tr key={l.id || i}>
                  {po || gr ? (
                    <>
                      <Table.Td>{l.itemCode || "—"}</Table.Td>
                      <Table.Td>{l.item}</Table.Td>
                      <Table.Td>{l.unit || "—"}</Table.Td>
                      <Table.Td ta="right">{p?.qty ?? "—"}</Table.Td>
                      <Table.Td ta="right">{p?.received ?? "—"}</Table.Td>
                      <Table.Td ta="right">
                        {gr ? l.qty : (p?.remaining ?? "—")}
                      </Table.Td>
                    </>
                  ) : bill ? (
                    <>
                      <Table.Td>{l.item}</Table.Td>
                      <Table.Td ta="right">{l.qty}</Table.Td>
                      <Table.Td ta="right">{won(net)}</Table.Td>
                      <Table.Td ta="right">
                        {row.values.taxType ? won(tax) : "미분리"}
                      </Table.Td>
                      <Table.Td ta="right">{won(net + tax)}</Table.Td>
                    </>
                  ) : (
                    <>
                      <Table.Td>{l.item}</Table.Td>
                      <Table.Td>{l.specification || "—"}</Table.Td>
                      <Table.Td>{l.unit || "—"}</Table.Td>
                      <Table.Td ta="right">{l.qty}</Table.Td>
                      <Table.Td ta="right">{won(l.price)}</Table.Td>
                      <Table.Td ta="right">{won(net)}</Table.Td>
                    </>
                  )}
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </div>
      {gr ? (
        <p className="muted">초안 수량은 확정 입고 누계에 포함되지 않습니다.</p>
      ) : (
        <div className="form-total">
          <span>
            {pr ? "예상 공급가액" : "공급가액"} <b>{won(total.net)}</b>
          </span>
          {!pr && (
            <>
              <span>
                부가세{" "}
                <b>
                  {row.values.taxType
                    ? won(total.tax)
                    : "기존 자료 · 세액 미분리"}
                </b>
              </span>
              <span>
                공급대가{" "}
                <strong>{won(row.values.grossAmount ?? total.gross)}</strong>
              </span>
            </>
          )}
        </div>
      )}
    </section>
  );
}
