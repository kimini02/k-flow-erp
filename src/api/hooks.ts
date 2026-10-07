import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { notifications } from "@mantine/notifications";
import type { ListParams, RecordData } from "../domain/types";
import { api } from "./client";
export function useRecords(resource: string, params: ListParams = {}) {
  return useQuery({
    queryKey: ["erp", resource, params],
    enabled: Boolean(resource),
    queryFn: () => api.list(resource, params),
  });
}
export function useRecord(resource: string, id: string | null) {
  return useQuery({
    queryKey: ["erp", resource, "detail", id],
    queryFn: () => api.get(resource, id!),
    enabled: !!id,
  });
}
export function useActions(resource: string, actor: string) {
  const qc = useQueryClient();
  const success = () => {
    void qc.invalidateQueries({ queryKey: ["erp"] });
    notifications.show({
      title: "처리 완료",
      message: "변경사항이 저장되었습니다.",
      color: "neutral",
    });
  };
  const onError = (error: Error) =>
    notifications.show({
      title: "처리할 수 없습니다",
      message: error.message,
      color: "red",
    });
  return {
    save: useMutation({
      mutationFn: (row: RecordData) => api.save(resource, row, actor),
      onSuccess: success,
      onError,
    }),
    transition: useMutation({
      mutationFn: ({ id, action }: { id: string; action: string }) =>
        api.transition(resource, id, action, actor),
      onSuccess: success,
      onError,
    }),
    approve: useMutation({
      mutationFn: ({
        id,
        action,
        reason,
      }: {
        id: string;
        action: string;
        reason?: string;
      }) => api.approve(id, action, actor, reason),
      onSuccess: success,
      onError,
    }),
  };
}
