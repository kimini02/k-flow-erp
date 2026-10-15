import type { Resource, Field, Column, Module, Transition } from "./types";
import { baseFields, documentColumns } from "./helpers";
export function document(
  key: string,
  module: string,
  label: string,
  prefix: string,
  extra: Partial<Resource> = {},
): Resource {
  return {
    key,
    module,
    label,
    singular: label,
    prefix,
    description: `${label} 현황을 조회하고 연결된 업무를 관리합니다.`,
    kind: "document",
    initial: "작성중",
    statuses: ["작성중", "확정", "완료"],
    fields: baseFields,
    columns: documentColumns,
    hasLines: true,
    ...extra,
  };
}
export function master(
  key: string,
  module: string,
  label: string,
  prefix: string,
  fields: Field[],
  columns: Column[],
  extra: Partial<Resource> = {},
): Resource {
  return {
    key,
    module,
    label,
    singular: label,
    prefix,
    description: `${label} 정보를 조회하고 등록 · 관리합니다.`,
    kind: "master",
    initial: "사용중",
    statuses: ["사용중", "비활성"],
    fields,
    columns: [
      { key: "id", label: "코드" },
      ...columns,
      { key: "status", label: "상태", type: "status" },
    ],
    ...extra,
  };
}
export const transition = (
  from: string,
  to: string,
  label: string,
  target?: string,
): Transition => ({ from: [from], to, label, target });
export function moduleConfig(
  key: string,
  label: string,
  caption: string,
  items: [string, string, string?, string?][],
): Module {
  return {
    key,
    label,
    caption,
    icon: key,
    items: items.map(([key, label, resource, status]) => ({
      key,
      label,
      resource,
      status,
    })),
  };
}
export const col = (
  key: string,
  label: string,
  type?: Column["type"],
): Column => ({ key, label, type });
export const fld = (
  key: string,
  label: string,
  type: Field["type"] = "text",
  options?: string[],
  min?: number,
): Field => ({ key, label, type, options, min, required: true });
