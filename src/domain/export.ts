import type { Column, RecordData } from "./types";
import { fieldValue } from "./helpers";
export function exportCsv(
  rows: RecordData[],
  columns: Column[],
  filename: string,
) {
  const cell = (v: unknown) => {
    let s = String(v ?? "");
    if (/^[=+@-]/.test(s)) s = "'" + s;
    return `"${s.replaceAll('"', '""')}"`;
  };
  const text =
    "\ufeff" +
    [
      columns.map((c) => cell(c.label)).join(","),
      ...rows.map((r) =>
        columns.map((c) => cell(fieldValue(r, c.key))).join(","),
      ),
    ].join("\r\n");
  const url = URL.createObjectURL(
    new Blob([text], { type: "text/csv;charset=utf-8;" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
