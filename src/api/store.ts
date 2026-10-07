import {
  isPurchase,
  normalizePurchasing,
  validatePurchase,
  fromPurchaseSource,
  receiptProgress,
  purchaseTotals,
  mockPartners,
  itemSnapshot,
} from "../features/purchasing/model";
import {
  saveEffects,
  transitionEffects,
  createLinked,
} from "../domain/effects";
import { createSeed, seedResource } from "./seed";
import type { Database } from "./seed";
import type {
  ListParams,
  ListResult,
  RecordData,
  Resource,
} from "../domain/types";
import { resources, resourceByKey } from "../app/catalog";
import { amountOf, blankRecord, fieldValue } from "../domain/helpers";
const STORAGE_KEY = "k-flow-erp-v1";
let memory: Database | undefined;
const clone = <T>(x: T): T => structuredClone(x);
export function database(): Database {
  if (memory) {
    normalizePurchasing(memory);
    return memory;
  }
  try {
    const saved =
      typeof localStorage !== "undefined"
        ? localStorage.getItem(STORAGE_KEY)
        : null;
    memory = saved ? JSON.parse(saved) : createSeed();
  } catch {
    memory = createSeed();
  }
  for (const r of resources) {
    if (!Array.isArray(memory![r.key])) memory![r.key] = seedResource(r);
  }
  normalizePurchasing(memory!);
  return memory!;
}
function commit(db: Database) {
  if (typeof localStorage !== "undefined")
    localStorage.setItem(STORAGE_KEY, JSON.stringify(db));
  memory = db;
}
export function resetDatabase() {
  memory = createSeed();
  normalizePurchasing(memory);
  commit(memory);
}
export function listRecords(resource: string, p: ListParams = {}): ListResult {
  const all = clone(database()[resource] ?? []).filter(
    (r) =>
      (!p.owner || r.owner === p.owner) &&
      (!p.participant ||
        r.owner === p.participant ||
        r.approvals.some((step) => step.name === p.participant)),
  );
  let rows = all.filter((r) => {
    const q = (p.search ?? "").trim().toLocaleLowerCase();
    return (
      (!q ||
        [
          r.id,
          r.title,
          r.partner,
          r.owner,
          r.department,
          ...Object.values(r.values),
        ]
          .join(" ")
          .toLocaleLowerCase()
          .includes(q)) &&
      (!p.status ||
        (p.status === "pending"
          ? ["상신", "결재중"].includes(r.status)
          : r.status === p.status)) &&
      (!p.owner || r.owner === p.owner) &&
      (!p.entity ||
        [r.partner, r.department, ...Object.values(r.values)].includes(
          p.entity,
        )) &&
      (!p.from || r.date >= p.from) &&
      (!p.to || r.date <= p.to)
    );
  });
  if (p.sort)
    rows.sort((a, b) => {
      const av = fieldValue(a, p.sort!),
        bv = fieldValue(b, p.sort!);
      return (
        (typeof av === "number" && typeof bv === "number"
          ? av - bv
          : String(av).localeCompare(String(bv), "ko", { numeric: true })) *
        (p.direction === "desc" ? -1 : 1)
      );
    });
  const total = rows.length;
  const size = p.size ?? 10;
  return {
    rows: rows.slice(((p.page ?? 1) - 1) * size, (p.page ?? 1) * size),
    total,
    all,
  };
}
export function getRecord(resource: string, id: string) {
  const row = database()[resource]?.find((r) => r.id === id);
  if (!row)
    throw new Error("문서를 찾을 수 없습니다. 목록을 새로고침해 주세요.");
  return clone(row);
}
function idFor(db: Database, r: Resource) {
  const ids = new Set((db[r.key] ?? []).map((x) => x.id));
  let n = (db[r.key]?.length ?? 0) + 1;
  while (ids.has(`${r.prefix}-2026-${String(n).padStart(4, "0")}`)) n++;
  return `${r.prefix}-2026-${String(n).padStart(4, "0")}`;
}
function audit(
  db: Database,
  resource: string,
  row: RecordData,
  actor: string,
  action: string,
) {
  const at = new Date().toLocaleString("sv-SE", { timeZone: "Asia/Seoul" });
  row.history.push({ actor, at, action });
  const ar = resourceByKey("audit-events");
  if (ar) {
    const event = blankRecord(ar, actor);
    event.id = idFor(db, ar);
    event.title = action;
    event.date = at.slice(0, 10);
    event.values = {
      time: at,
      event: action,
      reference: row.id,
      before: row.history.at(-2)?.action ?? "없음",
      after: `${row.status} · ${row.title}`,
    };
    event.status = "완료";
    event.links = [{ resource, id: row.id }];
    db["audit-events"] = [event, ...(db["audit-events"] ?? [])];
  }
}
export function validateRecord(r: Resource, row: RecordData) {
  for (const f of r.fields) {
    const v = fieldValue(row, f.key);
    if (f.required && (v === "" || v === null || v === undefined))
      throw new Error(`${f.label}을(를) 입력해 주세요.`);
    if (
      (f.type === "number" || f.type === "money") &&
      (!Number.isSafeInteger(Number(v)) || Number(v) < (f.min ?? 0))
    )
      throw new Error(
        `${f.label}은(는) ${f.min ?? 0} 이상의 정수로 입력해 주세요.`,
      );
  }
  if (
    row.dueDate &&
    row.date > row.dueDate &&
    r.kind !== "master" &&
    !["goods-receipts", "purchase-bills", "payments-out"].includes(r.key)
  )
    throw new Error("요청일/납기일은 작성일 이후여야 합니다.");
  if (r.hasLines && row.values.docType !== "연차신청서") {
    if (
      !row.lines.length ||
      row.lines.some(
        (l) =>
          !l.item ||
          (isPurchase(r.key)
            ? !Number.isFinite(l.qty) ||
              l.qty > 1e9 ||
              Math.abs(l.qty * 1000 - Math.round(l.qty * 1000)) > 0.00001 ||
              (l.unit !== "KG" && !Number.isInteger(l.qty))
            : !Number.isSafeInteger(l.qty)) ||
          l.qty <= 0 ||
          !Number.isSafeInteger(l.price) ||
          l.price < 0,
      )
    )
      throw new Error(
        isPurchase(r.key)
          ? "품목과 양수 수량(KG는 소수 3자리까지), 0 이상의 원 단위 단가를 입력해 주세요."
          : "품목, 양의 정수 수량, 0 이상의 원 단위 단가를 입력해 주세요.",
      );
    if (
      !Number.isSafeInteger(
        isPurchase(r.key) ? purchaseTotals(row).net : amountOf(row.lines),
      )
    )
      throw new Error("금액이 허용 범위를 초과했습니다.");
  }
  if (r.kind === "journal") {
    const debit = row.journal.reduce((n, l) => n + l.debit, 0),
      credit = row.journal.reduce((n, l) => n + l.credit, 0);
    if (
      !debit ||
      debit !== credit ||
      row.journal.length < 2 ||
      row.journal.some(
        (l) =>
          !l.account ||
          l.debit < 0 ||
          l.credit < 0 ||
          !Number.isSafeInteger(l.debit) ||
          !Number.isSafeInteger(l.credit) ||
          (l.debit > 0 && l.credit > 0),
      )
    )
      throw new Error(
        "차변과 대변은 같은 양의 금액이어야 하며, 한 행에는 차변 또는 대변만 입력할 수 있습니다.",
      );
  }
}
export function saveRecord(resource: string, input: RecordData, actor: string) {
  const r = resourceByKey(resource);
  if (!r || r.readOnly) throw new Error("등록할 수 없는 자료입니다.");
  const db = clone(database()),
    row = clone(input);
  if (isPurchase(resource)) {
    row.values.partnerId ||=
      mockPartners.find((p) => p.name === row.partner)?.id || "";
    row.lines = row.lines.map((l, i) => {
      const item = db.items?.find(
        (x) => x.id === l.itemId || (!l.itemId && x.title === l.item),
      );
      return {
        ...(item ? itemSnapshot(item) : {}),
        ...l,
        id: l.id || `${row.id || "draft"}:line:${i + 1}`,
      };
    });
    if (!row.title && resource !== "purchase-requests")
      row.title = `${row.partner} ${r.singular}`;
  }
  validateRecord(r, row);
  if (isPurchase(resource)) validatePurchase(resource, row, db);
  const existing = db[resource]?.find((x) => x.id === row.id);
  if (existing && !canEdit(r, existing, actor))
    throw new Error("현재 상태에서는 수정할 수 없습니다.");
  row.id = existing?.id ?? idFor(db, r);
  row.status = existing?.status ?? r.initial;
  row.owner = existing?.owner ?? actor;
  row.history = existing?.history ?? [];
  row.links = existing?.links ?? row.links;
  if (r.hasLines)
    row.amount = isPurchase(resource)
      ? purchaseTotals(row).net
      : amountOf(row.lines);
  if (["purchase-orders", "purchase-bills"].includes(resource)) {
    const totals = purchaseTotals(row);
    row.values.netAmount = totals.net;
    row.values.taxAmount = totals.tax;
    row.values.grossAmount = totals.gross;
  }
  if (r.kind === "journal")
    row.amount = row.journal.reduce((n, l) => n + l.debit, 0);
  if (r.kind === "approval" && !existing)
    row.approvals = [
      { name: actor, role: "기안", status: "기안" },
      { name: "김팀장", role: "팀장", status: "예정" },
      { name: "박본부장", role: "본부장", status: "예정" },
      { name: "이회계팀장", role: "재무 검토", status: "예정" },
      { name: "최이사", role: "최종 승인", status: "예정" },
    ];
  saveEffects(db, r, row, actor, !existing);
  audit(db, resource, row, actor, existing ? "문서 수정" : "문서 등록");
  db[resource] = existing
    ? db[resource].map((x) => (x.id === row.id ? row : x))
    : [row, ...(db[resource] ?? [])];
  commit(db);
  return clone(row);
}
export function canEdit(r: Resource, row: RecordData, actor: string) {
  return (
    !r.readOnly &&
    (r.kind === "master" ||
      (r.kind === "approval"
        ? row.owner === actor && ["작성중", "반려", "회수"].includes(row.status)
        : row.status === "작성중"))
  );
}
export function approvalAction(
  id: string,
  action: string,
  actor: string,
  reason = "",
) {
  const db = clone(database());
  const row = db.approvals.find((r) => r.id === id);
  if (!row) throw new Error("문서가 없습니다.");
  const pending = row.approvals.find((s) => s.status === "대기");
  if (action === "상신") {
    if (row.owner !== actor || !["작성중", "반려", "회수"].includes(row.status))
      throw new Error("기안자만 문서를 상신할 수 있습니다.");
    row.status = "상신";
    row.approvals.forEach(
      (s, i) => (s.status = i === 0 ? "기안" : i === 1 ? "대기" : "예정"),
    );
    delete row.values.rejectReason;
    for (const link of row.links) {
      const source = db[link.resource]?.find((r) => r.id === link.id);
      if (source && ["작성중", "반려"].includes(source.status))
        source.status = "승인대기";
    }
  } else if (action === "회수") {
    if (row.owner !== actor || !["상신", "결재중"].includes(row.status))
      throw new Error("진행 중인 문서의 기안자만 회수할 수 있습니다.");
    row.status = "회수";
    for (const link of row.links) {
      const source = db[link.resource]?.find((r) => r.id === link.id);
      if (source?.status === "승인대기") source.status = "작성중";
    }
    row.approvals.forEach((s, i) => (s.status = i === 0 ? "기안" : "예정"));
  } else {
    if (!["상신", "결재중"].includes(row.status) || pending?.name !== actor)
      throw new Error("현재 순서의 결재자만 처리할 수 있습니다.");
    if (action === "반려") {
      if (!reason.trim()) throw new Error("반려 사유를 입력해 주세요.");
      pending.status = "반려";
      row.status = "반려";
      row.values.rejectReason = reason;
      for (const link of row.links) {
        const source = db[link.resource]?.find((x) => x.id === link.id);
        if (source && source.status === "승인대기") {
          source.status = link.resource === "leave" ? "반려" : "작성중";
          audit(db, link.resource, source, actor, "전자결재 반려");
        }
      }
    } else if (action === "승인") {
      pending.status = "승인";
      const next = row.approvals.find((s) => s.status === "예정");
      if (next) {
        next.status = "대기";
        row.status = "결재중";
      } else {
        row.status = "승인완료";
        if (!row.links.length) {
          const key =
            row.values.docType === "연차신청서"
              ? "leave"
              : row.values.docType === "지출결의서"
                ? "journals"
                : "purchase-requests";
          const child = createLinked(db, key, row, "approvals", row.owner);
          child.status = key === "journals" ? "작성중" : "승인완료";
          child.lines = clone(row.lines);
          child.values = { ...child.values, ...row.values };
          if (key === "leave") {
            child.date = String(row.values.startDate ?? row.date);
            child.dueDate = String(row.values.endDate ?? row.dueDate);
            child.values.days = Math.max(
              1,
              Math.round(
                (Date.parse(child.dueDate) - Date.parse(child.date)) / 86400000,
              ) + 1,
            );
            child.values.remaining = 15 - Number(child.values.days);
            child.values.leaveType = "연차";
          }
          if (key === "journals") {
            child.values.category = "일반전표";
            child.journal = [
              { account: "6020 복리후생비", debit: row.amount, credit: 0 },
              { account: "1010 보통예금", debit: 0, credit: row.amount },
            ];
          }
          audit(db, key, child, actor, "결재 승인 문서 연결");
        }
        for (const link of row.links) {
          const source = db[link.resource]?.find((x) => x.id === link.id);
          if (source && source.status === "승인대기") {
            source.status = "승인완료";
            audit(db, link.resource, source, actor, "전자결재 승인완료");
          }
        }
      }
    } else throw new Error("지원하지 않는 결재 동작입니다.");
  }
  audit(db, "approvals", row, actor, `${action}${reason ? `: ${reason}` : ""}`);
  commit(db);
  return clone(row);
}
export function transitionRecord(
  resource: string,
  id: string,
  label: string,
  actor: string,
) {
  const db = clone(database());
  const r = resourceByKey(resource)!;
  const row = db[resource]?.find((x) => x.id === id);
  if (!row) throw new Error("문서를 찾을 수 없습니다.");
  const t = r.transitions?.find(
    (t) => t.label === label && t.from.includes(row.status),
  );
  if (!t) throw new Error("현재 상태에서 실행할 수 없는 동작입니다.");
  if (isPurchase(resource)) {
    validateRecord(r, row);
    validatePurchase(resource, row, db);
    if (
      t.target === "goods-receipts" &&
      !receiptProgress(row, db["goods-receipts"] || []).some(
        (l) => l.remaining > 0,
      )
    )
      throw new Error("입고할 잔여수량이 없습니다.");
  }
  row.status = t.to;
  transitionEffects(db, r, row, label, actor);
  if (t.target) {
    const target = resourceByKey(t.target);
    if (t.target === "approvals") {
      const link = row.links.find((l) => l.resource === "approvals");
      const previous = link && db.approvals.find((x) => x.id === link.id);
      if (previous && ["반려", "회수", "작성중"].includes(previous.status)) {
        previous.title = row.title;
        previous.lines = clone(row.lines);
        previous.amount = row.amount;
        previous.status = "상신";
        previous.approvals.forEach(
          (step, i) =>
            (step.status = i === 0 ? "기안" : i === 1 ? "대기" : "예정"),
        );
        delete previous.values.rejectReason;
        audit(db, "approvals", previous, actor, "결재 재상신");
      }
    }
    if (
      target &&
      (target.key === "goods-receipts" ||
        !row.links.some((l) => l.resource === target.key))
    ) {
      let child = clone(row);
      child.id = idFor(db, target);
      child.status = target.initial;
      child.title = row.title;
      child.owner = actor;
      child.history = [];
      child.links = [{ resource, id }];
      if (
        ["purchase-orders", "goods-receipts", "purchase-bills"].includes(
          target.key,
        )
      ) {
        child.values = {
          taxType: "일반과세 10%",
          paymentTerms: "증빙일로부터 30일",
        };
        child = fromPurchaseSource(
          target.key,
          child,
          row,
          resource,
          db["goods-receipts"] || [],
        );
        child.lines = child.lines
          .filter((l) => l.qty > 0)
          .map((l, i) => ({ ...l, id: `${child.id}:line:${i + 1}` }));
        if (target.key === "purchase-bills") {
          child.values.evidenceDate = child.date;
          child.values.evidenceNumber = "";
          const total = purchaseTotals(child);
          child.amount = total.net;
          child.values.netAmount = total.net;
          child.values.taxAmount = total.tax;
          child.values.grossAmount = total.gross;
        }
      }
      if (target.key === "payables") {
        const total = purchaseTotals(row);
        child.amount = total.gross;
        child.date = row.date;
        child.lines = [];
        child.values.billId = row.id;
        child.values.paidAmount = 0;
      }
      if (target.key === "payables" || target.key === "receivables") {
        child.values.balance = child.amount;
        child.values.aging = 0;
      }
      if (target.key === "work-orders") {
        child.values.bom = row.id;
        child.values.qty = 100;
        child.values.produced = 0;
        child.values.progress = 0;
        child.values.line = "A라인 · 조립";
        child.values.warehouse = "화성 생산창고";
      }
      if (target.key === "production-receipts") {
        child.values.qty = row.values.produced;
        child.values.warehouse = "화성 생산창고";
      }
      if (target.kind === "approval") {
        child.values.docType =
          resource === "leave" ? "연차신청서" : "구매요청서";
        child.status = "상신";
        child.approvals = [
          { name: actor, role: "기안", status: "기안" },
          { name: "김팀장", role: "팀장", status: "대기" },
          { name: "박본부장", role: "본부장", status: "예정" },
          { name: "이회계팀장", role: "재무 검토", status: "예정" },
          { name: "최이사", role: "최종 승인", status: "예정" },
        ];
      }
      if (target.kind === "approval") {
        saveEffects(db, target, child, actor, true);
        child.approvals.forEach(
          (step, i) =>
            (step.status = i === 0 ? "기안" : i === 1 ? "대기" : "예정"),
        );
      }
      db[target.key] = [child, ...(db[target.key] ?? [])];
      row.links.push({ resource: target.key, id: child.id });
      audit(db, target.key, child, actor, `${row.id}에서 생성`);
    }
  }
  audit(db, resource, row, actor, label);
  commit(db);
  return clone(row);
}
