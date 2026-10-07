import { Select } from "@mantine/core";
export function EntitySelect({
  label,
  data,
  value,
  onChange,
  required = false,
}: {
  label: string;
  data: string[];
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
}) {
  return (
    <Select
      label={label}
      aria-label={label}
      placeholder={`${label} 선택`}
      searchable
      clearable={!required}
      required={required}
      data={[...new Set(data)]}
      value={value || null}
      onChange={(v) => onChange(v ?? "")}
      nothingFoundMessage="검색 결과가 없습니다"
    />
  );
}
