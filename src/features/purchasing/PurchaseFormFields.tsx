import type { Dispatch, SetStateAction } from "react";
import {
  Alert,
  Button,
  Group,
  NumberInput,
  Select,
  Table,
  TextInput,
} from "@mantine/core";
import type { Line, RecordData, Resource } from "../../domain/types";
import { fieldValue, won } from "../../domain/helpers";
import { useRecords } from "../../api/hooks";
import {
  fromPurchaseSource,
  itemSnapshot,
  mockPartners,
  purchaseTotals,
  receiptProgress,
  sourceKey,
} from "./model";

type Props = {
  resource: Resource;
  draft: RecordData;
  setDraft: Dispatch<SetStateAction<RecordData>>;
};
export function PurchaseFormFields({ resource, draft, setDraft }: Props) {
  const key = resource.key,
    pr = key === "purchase-requests",
    po = key === "purchase-orders",
    gr = key === "goods-receipts",
    bill = key === "purchase-bills",
    pay = key === "payments-out";
  const itemsQuery = useRecords("items", { size: 10000 }),
    warehouseQuery = useRecords("warehouses", { size: 10000 }),
    departmentsQuery = useRecords("departments", { size: 10000 });
  const sourceResource = sourceKey(key, draft);
  const sourceQuery = useRecords(pay ? "payables" : sourceResource, {
    size: 10000,
  });
  const receiptQuery = useRecords(gr ? "goods-receipts" : "", { size: 10000 });
  const items = itemsQuery.data?.all || [],
    sources = sourceQuery.data?.all || [],
    receipts = receiptQuery.data?.all || [];
  const source = sources.find((s) => s.id === draft.values.sourceId);
  const progress =
    source && gr ? receiptProgress(source, receipts, draft.id) : [];
  const displayedLines =
    gr && source
      ? progress.map(
          (p) =>
            draft.lines.find((l) => l.sourceLineId === p.id) || {
              ...p,
              id: undefined,
              sourceLineId: p.id,
              qty: 0,
            },
        )
      : draft.lines;
  const totals = purchaseTotals(draft);
  const allocations = JSON.parse(
    String(draft.values.allocations || "{}"),
  ) as Record<string, number>;
  const allocated = Object.values(allocations).reduce((n, v) => n + v, 0);
  const set = (field: string, value: string | number) =>
    setDraft((d) =>
      field in d
        ? { ...d, [field]: value }
        : { ...d, values: { ...d.values, [field]: value } },
    );
  const setLine = (i: number, patch: Partial<Line>) =>
    setDraft((d) => ({
      ...d,
      lines: displayedLines.map((l, j) => (i === j ? { ...l, ...patch } : l)),
    }));
  const allowedSources = sources.filter((s) =>
    po
      ? ["승인완료", "발주완료"].includes(s.status)
      : gr
        ? ["확정", "부분입고", "입고완료"].includes(s.status) &&
          (s.id === draft.values.sourceId ||
            receiptProgress(s, receipts).some((l) => l.remaining > 0))
        : bill
          ? sourceResource === "goods-receipts"
            ? s.status === "입고완료"
            : ["확정", "부분입고", "입고완료"].includes(s.status)
          : true,
  );
  const setPartner = (id: string | null) => {
    const p = mockPartners.find((x) => x.id === id);
    setDraft((d) => ({
      ...d,
      partner: p?.name || "",
      values: {
        ...d.values,
        partnerId: p?.id || "",
        paymentTerms: p?.paymentTerms || d.values.paymentTerms || "",
        allocations: "{}",
      },
    }));
  };
  const errors = [
    itemsQuery.error,
    warehouseQuery.error,
    departmentsQuery.error,
    sourceQuery.error,
    receiptQuery.error,
  ].filter(Boolean);
  return (
    <>
      {errors.length > 0 && (
        <Alert color="red">
          참조 자료를 불러오지 못했습니다. 창을 다시 열어 주세요.
        </Alert>
      )}
      <h3 className="purchase-section-title">문서 기본정보</h3>
      {pay && (
        <p className="form-intro">
          은행에서 완료된 지급 내역을 기록합니다. 여기서 저장해도 은행 송금은
          실행되지 않습니다.
        </p>
      )}
      {bill && (
        <Group mb="md">
          <Select
            label="원천 종류"
            data={[
              { value: "goods-receipts", label: "입고 문서" },
              { value: "purchase-orders", label: "구매발주 PO" },
            ]}
            value={sourceResource}
            onChange={(v) =>
              setDraft((d) => ({
                ...d,
                partner: "",
                lines: [],
                values: {
                  ...d.values,
                  sourceResource: v || "goods-receipts",
                  sourceId: "",
                  partnerId: "",
                },
              }))
            }
          />
          <span className="muted">
            내부 매입 증빙 등록 · 전자세금계산서 발급 기능 아님
          </span>
        </Group>
      )}
      {(po || gr || bill) && (
        <Select
          mb="md"
          aria-label={
            po
              ? "원천 구매요청 PR (선택)"
              : gr
                ? "원천 구매발주 PO"
                : `원천 ${sourceResource === "goods-receipts" ? "입고 문서" : "구매발주 PO"}`
          }
          disabled={sourceQuery.isLoading || (gr && receiptQuery.isLoading)}
          searchable
          clearable={po}
          required={!po}
          label={
            po
              ? "원천 구매요청 PR (선택)"
              : gr
                ? "원천 구매발주 PO"
                : `원천 ${sourceResource === "goods-receipts" ? "입고 문서" : "구매발주 PO"}`
          }
          placeholder="원천문서를 선택하면 품목이 채워집니다"
          data={allowedSources.map((s) => ({
            value: s.id,
            label: `${s.id} · ${s.partner || s.title}`,
          }))}
          value={String(draft.values.sourceId || "") || null}
          onChange={(id) => {
            const s = sources.find((x) => x.id === id);
            if (s)
              setDraft((d) =>
                fromPurchaseSource(key, d, s, sourceResource, receipts),
              );
            else
              setDraft((d) => ({
                ...d,
                values: { ...d.values, sourceId: "", sourceResource },
              }));
          }}
        />
      )}
      <div className="form-grid">
        {resource.fields.map((f) => {
          const value = fieldValue(draft, f.key);
          if (f.key === "partner")
            return gr || bill ? (
              <TextInput
                key={f.key}
                aria-label="거래처"
                label="거래처"
                value={draft.partner}
                readOnly
                placeholder="원천문서에서 자동 표시"
              />
            ) : (
              <Select
                key={f.key}
                aria-label={f.label}
                label={f.label}
                required={f.required}
                searchable
                clearable={pr}
                data={mockPartners
                  .filter((p) => p.status === "사용중")
                  .map((p) => ({ value: p.id, label: p.name }))}
                value={
                  String(
                    draft.values.partnerId ||
                      mockPartners.find((p) => p.name === draft.partner)?.id ||
                      "",
                  ) || null
                }
                onChange={setPartner}
              />
            );
          if (f.type === "select")
            return (
              <Select
                key={f.key}
                aria-label={f.label}
                label={f.label}
                required={f.required}
                searchable
                data={
                  f.key === "warehouse"
                    ? (warehouseQuery.data?.all || [])
                        .filter((x) => x.status === "사용중")
                        .map((x) => x.title)
                    : f.key === "department"
                      ? (departmentsQuery.data?.all || []).map((x) => x.title)
                      : f.options || []
                }
                value={String(value) || null}
                onChange={(v) => set(f.key, v || "")}
              />
            );
          if (f.type === "money" || f.type === "number")
            return (
              <NumberInput
                key={f.key}
                aria-label={f.label}
                label={f.label}
                required={f.required}
                min={0}
                allowDecimal={false}
                thousandSeparator=","
                value={Number(value)}
                onChange={(v) => set(f.key, Number(v))}
              />
            );
          return (
            <TextInput
              key={f.key}
              aria-label={f.label}
              label={f.label}
              required={f.required}
              readOnly={f.key === "owner"}
              type={f.type === "date" ? "date" : "text"}
              value={String(value)}
              onChange={(e) => set(f.key, e.currentTarget.value)}
            />
          );
        })}
      </div>
      {pay ? (
        <section className="form-section">
          <h3>미지급 AP 배분</h3>
          <div className="purchase-grid-scroll">
            <Table className="purchase-edit-grid">
              <Table.Thead>
                <Table.Tr>
                  {[
                    "AP 번호",
                    "원천 매입문서",
                    "지급기한",
                    "원금",
                    "기존 지급액",
                    "미지급잔액",
                    "이번 지급액",
                  ].map((h, i) => (
                    <Table.Th key={h} ta={i >= 3 ? "right" : undefined}>
                      {h}
                    </Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {sources
                  .filter(
                    (s) =>
                      s.partner === draft.partner &&
                      Number(s.values.balance) > 0,
                  )
                  .map((s) => (
                    <Table.Tr key={s.id}>
                      <Table.Td>{s.id}</Table.Td>
                      <Table.Td>{String(s.values.billId || "—")}</Table.Td>
                      <Table.Td>{s.dueDate}</Table.Td>
                      <Table.Td ta="right">{won(s.amount)}</Table.Td>
                      <Table.Td ta="right">
                        {won(s.amount - Number(s.values.balance))}
                      </Table.Td>
                      <Table.Td ta="right">{won(s.values.balance)}</Table.Td>
                      <Table.Td>
                        <NumberInput
                          aria-label={`${s.id} 이번 지급액`}
                          min={0}
                          max={Number(s.values.balance)}
                          allowDecimal={false}
                          thousandSeparator=","
                          value={allocations[s.id] || 0}
                          onChange={(v) =>
                            set(
                              "allocations",
                              JSON.stringify({
                                ...allocations,
                                [s.id]: Number(v),
                              }),
                            )
                          }
                        />
                      </Table.Td>
                    </Table.Tr>
                  ))}
              </Table.Tbody>
            </Table>
          </div>
          {!sources.some(
            (s) => s.partner === draft.partner && Number(s.values.balance) > 0,
          ) && <p className="muted">선택한 거래처의 미지급 AP가 없습니다.</p>}
          <div className="form-total">
            <span>
              배분합계 <b>{won(allocated)}</b>
            </span>
            <span>
              지급액 <b>{won(draft.amount)}</b>
            </span>
            <span>
              차액 <strong>{won(draft.amount - allocated)}</strong>
            </span>
          </div>
          {allocated !== draft.amount && (
            <Alert color="orange" mt="sm">
              배분합계와 지급액이 일치해야 저장할 수 있습니다.
            </Alert>
          )}
        </section>
      ) : (
        <section className="form-section">
          <h3>품목 정보 {pr && <small>예상 공급가액</small>}</h3>
          <div className="purchase-grid-scroll">
            <Table className="purchase-edit-grid">
              <Table.Thead>
                <Table.Tr>
                  {(gr
                    ? [
                        "품목코드",
                        "품목명",
                        "단위",
                        "발주수량",
                        "기존 입고수량",
                        "잔여수량",
                        "이번 입고수량",
                      ]
                    : bill
                      ? ["품목", "수량", "공급가액", "세액", "합계"]
                      : pr
                        ? [
                            "품목",
                            "규격",
                            "단위",
                            "요청수량",
                            "예상단가",
                            "예상금액",
                            "",
                          ]
                        : [
                            "품목코드",
                            "품목명",
                            "규격",
                            "단위",
                            "발주수량",
                            "단가",
                            "공급가액",
                            "",
                          ]
                  ).map((h, i) => (
                    <Table.Th
                      key={`${h}${i}`}
                      ta={
                        i >= (gr ? 3 : bill ? 1 : pr ? 3 : 4)
                          ? "right"
                          : undefined
                      }
                    >
                      {h}
                    </Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {displayedLines.map((l, i) => {
                  const quantity = (
                    <NumberInput
                      aria-label={`${gr ? "이번 입고수량" : bill ? "매입수량" : pr ? "요청수량" : "발주수량"} ${i + 1}`}
                      min={gr ? 0 : l.unit === "KG" ? 0.001 : 1}
                      max={
                        gr
                          ? progress.find((p) => p.id === l.sourceLineId)
                              ?.remaining
                          : undefined
                      }
                      decimalScale={3}
                      allowDecimal={l.unit === "KG"}
                      thousandSeparator=","
                      value={l.qty}
                      onChange={(v) => setLine(i, { qty: Number(v) })}
                    />
                  );
                  const net = Math.round(l.qty * l.price),
                    tax =
                      draft.values.taxType === "일반과세 10%"
                        ? Math.round(net * 0.1)
                        : 0;
                  const selected = items.find(
                    (x) =>
                      x.id === l.itemId || (!l.itemId && x.title === l.item),
                  );
                  const select = (
                    <Select
                      aria-label={`품목 ${i + 1}`}
                      searchable
                      placeholder="품목 선택"
                      data={items
                        .filter(
                          (x) => x.status === "사용중" || x.id === selected?.id,
                        )
                        .map((x) => ({ value: x.id, label: x.title }))}
                      value={selected?.id || null}
                      onChange={(id) => {
                        const item = items.find((x) => x.id === id);
                        if (item) setLine(i, itemSnapshot(item));
                      }}
                    />
                  );
                  const p = progress.find((x) => x.id === l.sourceLineId);
                  return (
                    <Table.Tr key={l.id || i}>
                      {gr ? (
                        <>
                          <Table.Td>{l.itemCode || "—"}</Table.Td>
                          <Table.Td>{l.item}</Table.Td>
                          <Table.Td>{l.unit || "—"}</Table.Td>
                          <Table.Td ta="right">{p?.qty ?? "—"}</Table.Td>
                          <Table.Td ta="right">{p?.received ?? "—"}</Table.Td>
                          <Table.Td ta="right">{p?.remaining ?? "—"}</Table.Td>
                          <Table.Td>{quantity}</Table.Td>
                        </>
                      ) : bill ? (
                        <>
                          <Table.Td>{l.item}</Table.Td>
                          <Table.Td>{quantity}</Table.Td>
                          <Table.Td ta="right">{won(net)}</Table.Td>
                          <Table.Td ta="right">{won(tax)}</Table.Td>
                          <Table.Td ta="right">{won(net + tax)}</Table.Td>
                        </>
                      ) : (
                        <>
                          {po && (
                            <Table.Td>
                              {l.itemCode || selected?.id || "—"}
                            </Table.Td>
                          )}
                          <Table.Td>{select}</Table.Td>
                          <Table.Td>{l.specification || "—"}</Table.Td>
                          <Table.Td>{l.unit || "—"}</Table.Td>
                          <Table.Td>{quantity}</Table.Td>
                          <Table.Td>
                            <NumberInput
                              aria-label={`${pr ? "예상단가" : "단가"} ${i + 1}`}
                              min={0}
                              allowDecimal={false}
                              thousandSeparator=","
                              value={l.price}
                              onChange={(v) => setLine(i, { price: Number(v) })}
                            />
                          </Table.Td>
                          <Table.Td ta="right">{won(net)}</Table.Td>
                          <Table.Td>
                            <Button
                              variant="subtle"
                              color="gray"
                              size="compact-xs"
                              aria-label={`품목 ${i + 1} 삭제`}
                              disabled={draft.lines.length === 1}
                              onClick={() =>
                                setDraft((d) => ({
                                  ...d,
                                  lines: d.lines.filter((_, j) => j !== i),
                                }))
                              }
                            >
                              삭제
                            </Button>
                          </Table.Td>
                        </>
                      )}
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          </div>
          {!draft.lines.length && (
            <p className="muted">원천문서를 선택해 주세요.</p>
          )}
          {(pr || po) && (
            <Button
              variant="subtle"
              size="xs"
              mt="sm"
              onClick={() =>
                setDraft((d) => ({
                  ...d,
                  lines: [
                    ...d.lines,
                    { id: crypto.randomUUID(), item: "", qty: 1, price: 0 },
                  ],
                }))
              }
            >
              품목 추가
            </Button>
          )}
          {gr ? (
            <p className="muted">
              수량 0인 행은 이번 입고에서 제외됩니다. 저장은 초안이며 입고 확정
              시에만 누적 수량과 재고에 반영됩니다.
            </p>
          ) : (
            <div className="form-total">
              <span>
                {pr ? "예상 공급가액" : "공급가액"} <b>{won(totals.net)}</b>
              </span>
              {!pr && (
                <>
                  <span>
                    부가세 <b>{won(totals.tax)}</b>
                  </span>
                  <span>
                    {bill ? "공급대가" : "합계"}{" "}
                    <strong>{won(totals.gross)}</strong>
                  </span>
                </>
              )}
            </div>
          )}
          {!pr && !gr && (
            <p className="muted">
              세액은 품목별 공급가액 기준 원 단위 반올림으로 표시합니다.
            </p>
          )}
        </section>
      )}
    </>
  );
}
