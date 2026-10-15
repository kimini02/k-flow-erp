import { PurchaseFormFields } from "../features/purchasing/PurchaseFormFields";
import { initializePurchase, isPurchase } from "../features/purchasing/model";
import { useState, type FormEvent, type ReactNode } from "react";
import { useMediaQuery } from "@mantine/hooks";
import {
  Drawer,
  TextInput,
  NumberInput,
  Textarea,
  Button,
  Group,
  ActionIcon,
  Select,
} from "@mantine/core";
import { IconArrowLeft, IconPlus, IconTrash } from "@tabler/icons-react";
import type { RecordData, Resource } from "../domain/types";
import { modules, resourceByKey } from "../app/catalog";
import { purchaseTotals } from "../features/purchasing/model";
import { StatusBadge } from "./StatusBadge";
import { PARTNERS, ITEMS } from "../domain/types";
import {
  blankRecord,
  fieldValue,
  amountOf,
  won,
  taxOf,
} from "../domain/helpers";
import { AmountAllocationTable } from "./AmountAllocationTable";
import { useRecords } from "../api/hooks";
import { useActions } from "../api/hooks";
import { useSession } from "../app/AppLayout";
import { EntitySelect } from "./EntitySelect";
import { validateRecord } from "../api/store";
const ACCOUNTS = [
  "1010 보통예금",
  "1130 매출채권",
  "1310 원재료",
  "1330 제품",
  "2110 매입채무",
  "3010 자본금",
  "4010 제품매출",
  "5010 매출원가",
  "6010 급여",
  "6020 복리후생비",
];
// Documents with line grids get a full editor page; short master data uses a side panel.
export const usesEditorPage = (r: Resource) =>
  !!r.hasLines ||
  isPurchase(r.key) ||
  ["journal", "payment", "approval"].includes(r.kind ?? "");
export const editorPath = (moduleKey: string, screen: string, id?: string) =>
  `/${moduleKey}/${screen}/${id ? `${encodeURIComponent(id)}/edit` : "new"}`;
export function editorUrl(resourceKey: string, id?: string) {
  const r = resourceByKey(resourceKey);
  const mod = modules.find((m) => m.key === r?.module);
  const item =
    mod?.items.find(
      (i) => i.resource === resourceKey && !i.status && !i.ownerOnly,
    ) ?? mod?.items.find((i) => i.resource === resourceKey);
  return editorPath(r?.module ?? "", item?.key ?? "", id);
}
export function DocumentForm({
  resource,
  record,
  onClose,
  onSaved,
  variant = "panel",
  backLabel = "목록",
}: {
  resource: Resource;
  record?: RecordData;
  onClose: () => void;
  onSaved: (r: RecordData) => void;
  variant?: "page" | "panel";
  backLabel?: string;
}) {
  const narrow = useMediaQuery("(max-width: 800px)", undefined, {
    getInitialValueInEffect: false,
  });
  const { actor } = useSession();
  const [draft, setDraft] = useState<RecordData>(() =>
    isPurchase(resource.key)
      ? initializePurchase(resource.key, record ?? blankRecord(resource, actor))
      : structuredClone(record ?? blankRecord(resource, actor)),
  );
  const [error, setError] = useState("");
  const { save } = useActions(resource.key, actor);
  const sourceQuery = useRecords(resource.source ?? "", { size: 10000 });
  const sources = sourceQuery.data?.all ?? [];
  const itemQuery = useRecords("items", { size: 10000 });
  const warehouseQuery = useRecords("warehouses", { size: 10000 });
  const departmentQuery = useRecords("departments", { size: 10000 });
  const itemOptions = [
    ...new Set([
      ...ITEMS,
      ...(itemQuery.data?.all ?? [])
        .filter((r) => r.status === "사용중")
        .map((r) => r.title),
    ]),
  ];
  const warehouseOptions =
    warehouseQuery.data?.all
      .filter((r) => r.status === "사용중")
      .map((r) => r.title) ?? [];
  const departmentOptions =
    departmentQuery.data?.all
      .filter((r) => r.status === "사용중")
      .map((r) => r.title) ?? [];
  const allocations = JSON.parse(
    String(draft.values.allocations ?? "{}"),
  ) as Record<string, number>;
  const set = (key: string, value: string | number) =>
    setDraft((d) =>
      key === "partner"
        ? {
            ...d,
            partner: String(value),
            values: { ...d.values, allocations: "{}" },
          }
        : key in d
          ? { ...d, [key]: value }
          : { ...d, values: { ...d.values, [key]: value } },
    );
  const leave = draft.values.docType === "연차신청서";
  async function submit() {
    try {
      const row = {
        ...draft,
        lines: leave
          ? []
          : resource.key === "goods-receipts"
            ? draft.lines.filter((l) => l.qty > 0)
            : draft.lines,
      };
      validateRecord(resource, row);
      const result = await save.mutateAsync(row);
      onSaved(result);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  const fields = (
    <>
      {isPurchase(resource.key) ? (
        <PurchaseFormFields
          resource={resource}
          draft={draft}
          setDraft={setDraft}
        />
      ) : (
        <>
          {resource.source && resource.kind !== "payment" && (
            <Select
              mb="md"
              label="원천문서"
              placeholder="관련 문서를 선택하면 내용이 채워집니다"
              searchable
              data={sources.map((r) => ({
                value: r.id,
                label: `${r.id} · ${r.title}`,
              }))}
              value={String(draft.values.sourceId ?? "") || null}
              onChange={(id) => {
                const s = sources.find((r) => r.id === id);
                if (s)
                  setDraft((d) => ({
                    ...d,
                    title: s.title,
                    partner: s.partner,
                    lines: structuredClone(s.lines),
                    amount: s.amount,
                    values: { ...d.values, sourceId: id ?? "" },
                  }));
              }}
            />
          )}
          <div className="form-grid">
            {resource.fields.map((f) =>
              f.type === "select" ? (
                <EntitySelect
                  key={f.key}
                  label={f.label}
                  required={f.required}
                  value={String(fieldValue(draft, f.key))}
                  onChange={(v) => set(f.key, v)}
                  data={
                    f.key.toLowerCase().includes("warehouse") &&
                    warehouseOptions.length
                      ? warehouseOptions
                      : f.key === "department" && departmentOptions.length
                        ? departmentOptions
                        : f.options === ITEMS
                          ? itemOptions
                          : (f.options ?? (f.key === "partner" ? PARTNERS : []))
                  }
                />
              ) : f.type === "number" || f.type === "money" ? (
                <NumberInput
                  key={f.key}
                  label={f.label}
                  required={f.required}
                  min={f.min ?? 0}
                  allowDecimal={false}
                  thousandSeparator=","
                  value={Number(fieldValue(draft, f.key))}
                  onChange={(v) => set(f.key, Number(v))}
                />
              ) : (
                <TextInput
                  key={f.key}
                  label={f.label}
                  required={f.required}
                  type={f.type === "date" ? "date" : "text"}
                  value={String(fieldValue(draft, f.key))}
                  onChange={(e) => set(f.key, e.currentTarget.value)}
                />
              ),
            )}
          </div>
          {resource.kind === "payment" && (
            <AmountAllocationTable
              rows={sources.filter(
                (r) =>
                  r.partner === draft.partner && Number(r.values.balance) > 0,
              )}
              value={allocations}
              onChange={(v) =>
                setDraft((d) => ({
                  ...d,
                  amount: Object.values(v).reduce((n, x) => n + x, 0),
                  values: { ...d.values, allocations: JSON.stringify(v) },
                }))
              }
            />
          )}{" "}
          {resource.hasLines && !leave && (
            <section className="form-section">
              <h3>
                품목 정보 <small>공급가액 기준 · 부가세 별도</small>
              </h3>
              {draft.lines.map((line, i) => (
                <div className="line-form" key={i}>
                  <Select
                    searchable
                    label={i === 0 ? "품목" : undefined}
                    aria-label={`품목 ${i + 1}`}
                    data={itemOptions}
                    placeholder="품목 선택"
                    value={line.item || null}
                    onChange={(v) =>
                      setDraft((d) => ({
                        ...d,
                        lines: d.lines.map((l, j) =>
                          j === i ? { ...l, item: v ?? "" } : l,
                        ),
                      }))
                    }
                  />
                  <NumberInput
                    label={i === 0 ? "수량" : undefined}
                    aria-label={`수량 ${i + 1}`}
                    min={1}
                    allowDecimal={false}
                    value={line.qty}
                    onChange={(v) =>
                      setDraft((d) => ({
                        ...d,
                        lines: d.lines.map((l, j) =>
                          j === i ? { ...l, qty: Number(v) } : l,
                        ),
                      }))
                    }
                  />
                  <NumberInput
                    label={i === 0 ? "단가 (원)" : undefined}
                    aria-label={`단가 ${i + 1}`}
                    min={0}
                    allowDecimal={false}
                    thousandSeparator=","
                    value={line.price}
                    onChange={(v) =>
                      setDraft((d) => ({
                        ...d,
                        lines: d.lines.map((l, j) =>
                          j === i ? { ...l, price: Number(v) } : l,
                        ),
                      }))
                    }
                  />
                  <ActionIcon
                    variant="subtle"
                    color="gray"
                    aria-label={`품목 ${i + 1} 삭제`}
                    disabled={draft.lines.length === 1}
                    onClick={() =>
                      setDraft((d) => ({
                        ...d,
                        lines: d.lines.filter((_, j) => j !== i),
                      }))
                    }
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </div>
              ))}
              <Button
                variant="subtle"
                size="xs"
                mt="sm"
                leftSection={<IconPlus size={14} />}
                onClick={() =>
                  setDraft((d) => ({
                    ...d,
                    lines: [...d.lines, { item: "", qty: 1, price: 0 }],
                  }))
                }
              >
                품목 추가
              </Button>
              <div className="form-total">
                <span>
                  공급가액 <b>{won(amountOf(draft.lines))}</b>
                </span>
                <span>
                  부가세 <b>{won(taxOf(amountOf(draft.lines)))}</b>
                </span>
                <span>
                  합계{" "}
                  <strong>
                    {won(amountOf(draft.lines) + taxOf(amountOf(draft.lines)))}
                  </strong>
                </span>
              </div>
            </section>
          )}
          {leave && (
            <div className="form-grid form-section">
              <TextInput
                label="휴가 시작일"
                type="date"
                required
                value={String(draft.values.startDate ?? draft.date)}
                onChange={(e) => set("startDate", e.currentTarget.value)}
              />
              <TextInput
                label="휴가 종료일"
                type="date"
                required
                value={String(draft.values.endDate ?? draft.dueDate)}
                onChange={(e) => set("endDate", e.currentTarget.value)}
              />
            </div>
          )}
          {resource.kind === "journal" && (
            <section className="form-section">
              <h3>분개 내역</h3>
              {draft.journal.map((line, i) => (
                <div className="line-form" key={i}>
                  <Select
                    label={i === 0 ? "계정과목" : undefined}
                    aria-label={`계정과목 ${i + 1}`}
                    data={ACCOUNTS}
                    value={line.account || null}
                    searchable
                    onChange={(v) =>
                      setDraft((d) => ({
                        ...d,
                        journal: d.journal.map((l, j) =>
                          j === i ? { ...l, account: v ?? "" } : l,
                        ),
                      }))
                    }
                  />
                  <NumberInput
                    label={i === 0 ? "차변" : undefined}
                    aria-label={`차변 ${i + 1}`}
                    min={0}
                    allowDecimal={false}
                    value={line.debit}
                    onChange={(v) =>
                      setDraft((d) => ({
                        ...d,
                        journal: d.journal.map((l, j) =>
                          j === i ? { ...l, debit: Number(v) } : l,
                        ),
                      }))
                    }
                  />
                  <NumberInput
                    label={i === 0 ? "대변" : undefined}
                    aria-label={`대변 ${i + 1}`}
                    min={0}
                    allowDecimal={false}
                    value={line.credit}
                    onChange={(v) =>
                      setDraft((d) => ({
                        ...d,
                        journal: d.journal.map((l, j) =>
                          j === i ? { ...l, credit: Number(v) } : l,
                        ),
                      }))
                    }
                  />
                  <ActionIcon
                    aria-label={`분개 ${i + 1} 삭제`}
                    disabled={draft.journal.length <= 2}
                    onClick={() =>
                      setDraft((d) => ({
                        ...d,
                        journal: d.journal.filter((_, j) => i !== j),
                      }))
                    }
                  >
                    <IconTrash size={15} />
                  </ActionIcon>
                </div>
              ))}
              <Group justify="space-between" mt="sm">
                <Button
                  variant="subtle"
                  onClick={() =>
                    setDraft((d) => ({
                      ...d,
                      journal: [
                        ...d.journal,
                        { account: "", debit: 0, credit: 0 },
                      ],
                    }))
                  }
                >
                  분개 행 추가
                </Button>
                <span>
                  차대변 차이:{" "}
                  <b>
                    {won(
                      draft.journal.reduce((n, l) => n + l.debit - l.credit, 0),
                    )}
                  </b>
                </span>
              </Group>
            </section>
          )}
        </>
      )}
      <Textarea
        mt="lg"
        label="비고"
        placeholder="업무에 필요한 참고사항을 입력하세요"
        minRows={2}
        value={String(draft.values.memo ?? "")}
        onChange={(e) => set("memo", e.currentTarget.value)}
      />
    </>
  );
  const submitLabel = record
    ? "변경사항 저장"
    : resource.kind === "approval"
      ? "임시저장"
      : "저장";
  const blocked =
    resource.key === "payments-out" &&
    (draft.amount <= 0 ||
      Object.values(allocations).reduce((n, v) => n + v, 0) !== draft.amount);
  const totals: ReactNode = isPurchase(resource.key) ? (
    resource.key === "payments-out" ? (
      <span>
        지급액 <b>{won(draft.amount)}</b>
      </span>
    ) : (
      <span>
        공급가 {won(purchaseTotals(draft).net)} · 부가세{" "}
        {won(purchaseTotals(draft).tax)} · 합계{" "}
        <b>{won(purchaseTotals(draft).gross)}</b>
      </span>
    )
  ) : resource.kind === "journal" ? (
    <span>
      차대변 차이{" "}
      <b>{won(draft.journal.reduce((n, l) => n + l.debit - l.credit, 0))}</b>
    </span>
  ) : resource.kind === "payment" ? (
    <span>
      금액 <b>{won(draft.amount)}</b>
    </span>
  ) : resource.hasLines && !leave ? (
    <span>
      합계 <b>{won(amountOf(draft.lines) + taxOf(amountOf(draft.lines)))}</b>
    </span>
  ) : null;
  const actions = (
    <>
      {error && (
        <span className="editor-error" role="alert">
          {error}
        </span>
      )}
      <Button variant="default" onClick={onClose}>
        취소
      </Button>
      <Button type="submit" loading={save.isPending} disabled={blocked}>
        {submitLabel}
      </Button>
    </>
  );
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    void submit();
  };
  if (variant === "page")
    return (
      <form className="editor-page" onSubmit={onSubmit}>
        <header className="editor-header">
          <Button
            variant="subtle"
            color="neutral"
            size="xs"
            leftSection={<IconArrowLeft size={14} />}
            onClick={onClose}
          >
            {backLabel}
          </Button>
          <div>
            <h1>
              {record ? `${record.id} 수정` : `신규 ${resource.singular}`}
            </h1>
            <StatusBadge status={draft.status} />
          </div>
          <p>
            * 표시는 필수 입력입니다. 저장한 뒤 상세 화면에서 다음 업무를
            진행합니다.
          </p>
        </header>
        <div className="editor-body label-left">{fields}</div>
        <div className="editor-actions">
          <div className="editor-totals">{totals}</div>
          {actions}
        </div>
      </form>
    );
  return (
    <Drawer
      opened
      onClose={onClose}
      title={
        <strong>
          {record ? "수정" : "신규 등록"} · {resource.singular}
        </strong>
      }
      size={narrow ? "100%" : 480}
      withOverlay={narrow}
      className="form-panel"
      closeButtonProps={{ "aria-label": "등록 창 닫기" }}
    >
      <form className="panel-form label-left" onSubmit={onSubmit}>
        {fields}
        <div className="panel-actions">
          <div className="editor-totals">{totals}</div>
          {actions}
        </div>
      </form>
    </Drawer>
  );
}
