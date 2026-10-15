import { Table, NumberInput, Button } from "@mantine/core";
import type { RecordData } from "../domain/types";
import { won } from "../domain/helpers";
export function AmountAllocationTable({
  rows,
  value,
  onChange,
}: {
  rows: RecordData[];
  value: Record<string, number>;
  onChange: (value: Record<string, number>) => void;
}) {
  return (
    <section className="form-section">
      <h3>미결제 문서 배분</h3>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>문서번호</Table.Th>
            <Table.Th ta="right">미결제 잔액</Table.Th>
            <Table.Th ta="right">배분액 (원)</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>{r.id}</Table.Td>
              <Table.Td ta="right">{won(Number(r.values.balance))}</Table.Td>
              <Table.Td>
                <NumberInput
                  aria-label={`${r.id} 배분액`}
                  min={0}
                  max={Number(r.values.balance)}
                  allowDecimal={false}
                  thousandSeparator=","
                  value={value[r.id] ?? 0}
                  onChange={(v) => onChange({ ...value, [r.id]: Number(v) })}
                />
              </Table.Td>
              <Table.Td>
                <Button
                  size="compact-xs"
                  variant="subtle"
                  onClick={() =>
                    onChange({ ...value, [r.id]: Number(r.values.balance) })
                  }
                >
                  전액
                </Button>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {!rows.length && (
        <p className="muted">거래처를 선택하면 미결제 문서가 표시됩니다.</p>
      )}
      <div className="detail-total">
        배분 합계{" "}
        <strong>{won(Object.values(value).reduce((n, v) => n + v, 0))}</strong>
      </div>
    </section>
  );
}
