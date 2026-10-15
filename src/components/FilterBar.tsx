import { TextInput, Select, Button } from "@mantine/core";
import { IconSearch, IconRefresh } from "@tabler/icons-react";
import type { ListParams } from "../domain/types";
export function FilterBar({
  params,
  onChange,
  statuses,
  entities,
  entityLabel = "거래처",
  fixedStatus,
}: {
  params: ListParams;
  onChange: (p: ListParams) => void;
  statuses: string[];
  entities: string[];
  entityLabel?: string;
  fixedStatus?: boolean;
}) {
  return (
    <div className="filter-bar">
      <TextInput
        className="list-search"
        aria-label="목록 검색"
        placeholder="문서번호, 제목, 담당자 검색"
        leftSection={<IconSearch size={15} />}
        value={params.search ?? ""}
        onChange={(e) =>
          onChange({ ...params, search: e.currentTarget.value, page: 1 })
        }
      />
      <Select
        className="filter-select"
        aria-label={`${entityLabel} 필터`}
        placeholder={`전체 ${entityLabel}`}
        clearable
        searchable
        data={[...new Set(entities)].filter(Boolean)}
        value={params.entity ?? null}
        onChange={(v) => onChange({ ...params, entity: v ?? "", page: 1 })}
      />
      {!fixedStatus && (
        <Select
          className="filter-select"
          aria-label="상태 필터"
          placeholder="전체 상태"
          clearable
          data={statuses}
          value={params.status ?? null}
          onChange={(v) => onChange({ ...params, status: v ?? "", page: 1 })}
        />
      )}
      <div className="date-range">
        <TextInput
          type="date"
          aria-label="조회 시작일"
          value={params.from ?? ""}
          onChange={(e) =>
            onChange({ ...params, from: e.currentTarget.value, page: 1 })
          }
        />
        <span aria-hidden="true">~</span>
        <TextInput
          type="date"
          aria-label="조회 종료일"
          min={params.from}
          value={params.to ?? ""}
          onChange={(e) =>
            onChange({ ...params, to: e.currentTarget.value, page: 1 })
          }
        />
      </div>
      <Button
        variant="default"
        aria-label="필터 초기화"
        leftSection={<IconRefresh size={14} />}
        onClick={() => onChange({ page: 1, size: params.size })}
      >
        초기화
      </Button>
    </div>
  );
}
