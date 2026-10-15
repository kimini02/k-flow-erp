import type { ListParams, ListResult, RecordData } from "../domain/types";
import {
  listRecords,
  getRecord,
  saveRecord,
  transitionRecord,
  approvalAction,
} from "./store";
export interface ErpClient {
  list: (resource: string, params?: ListParams) => Promise<ListResult>;
  get: (resource: string, id: string) => Promise<RecordData>;
  save: (
    resource: string,
    row: RecordData,
    actor: string,
  ) => Promise<RecordData>;
  transition: (
    resource: string,
    id: string,
    action: string,
    actor: string,
  ) => Promise<RecordData>;
  approve: (
    id: string,
    action: string,
    actor: string,
    reason?: string,
  ) => Promise<RecordData>;
}
const delay = () =>
  new Promise<void>((resolve, reject) =>
    setTimeout(() => {
      if (
        typeof sessionStorage !== "undefined" &&
        sessionStorage.getItem("kflow-network-error") === "1"
      )
        reject(new Error("네트워크 연결을 확인한 뒤 다시 시도해 주세요."));
      else resolve();
    }, 180),
  );
const mock: ErpClient = {
  list: async (r, p) => {
    await delay();
    return listRecords(r, p);
  },
  get: async (r, id) => {
    await delay();
    return getRecord(r, id);
  },
  save: async (r, row, a) => {
    await delay();
    return saveRecord(r, row, a);
  },
  transition: async (r, id, action, a) => {
    await delay();
    return transitionRecord(r, id, action, a);
  },
  approve: async (id, action, a, reason) => {
    await delay();
    return approvalAction(id, action, a, reason);
  },
};
async function request<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${import.meta.env.VITE_API_URL ?? "/api"}${path}`, {
    method: body ? "POST" : "GET",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
  return res.json();
}
const rest: ErpClient = {
  list: (r, p) =>
    request(
      `/resources/${r}?${new URLSearchParams(
        Object.entries(p ?? {})
          .filter(([, v]) => v !== undefined && v !== "")
          .map(([k, v]) => [k, String(v)]),
      )}`,
    ),
  get: (r, id) => request(`/resources/${r}/${encodeURIComponent(id)}`),
  save: (r, row, actor) => request(`/resources/${r}`, { record: row, actor }),
  transition: (r, id, action, actor) =>
    request(`/resources/${r}/${encodeURIComponent(id)}/transitions`, {
      action,
      actor,
    }),
  approve: (id, action, actor, reason) =>
    request(`/approvals/${encodeURIComponent(id)}/actions`, {
      action,
      actor,
      reason,
    }),
};
export const api: ErpClient =
  import.meta.env.VITE_API_MODE === "rest" ? rest : mock;
