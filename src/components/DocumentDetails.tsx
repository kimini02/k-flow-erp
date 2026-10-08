import { PurchaseDetailLines } from "../features/purchasing/PurchaseDetailLines";
import { isPurchase, receiptProgress } from "../features/purchasing/model";
import { useRecords } from "../api/hooks";
import { Fragment, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  Alert,
  Button,
  Group,
  Loader,
  Table,
  Textarea,
  Modal,
  Tabs,
} from "@mantine/core";
import {
  IconExternalLink,
  IconPencil,
  IconArrowLeft,
  IconFileDescription,
  IconChevronRight,
} from "@tabler/icons-react";
import type { RecordData, Resource } from "../domain/types";
import { fieldValue, won } from "../domain/helpers";
import { useRecord, useActions } from "../api/hooks";
import { useSession } from "../app/AppLayout";
import { modules, resourceByKey } from "../app/catalog";
import { canEdit } from "../api/store";
import {
  DetailDrawer,
  fromOverlayAbove,
  hasOverlayAbove,
} from "./DetailDrawer";
import { StatusBadge } from "./StatusBadge";
import { ApprovalTimeline } from "./ApprovalTimeline";
import { DocumentFlow, hasDocumentSteps } from "./DocumentFlow";
export function documentUrl(resource: string, id: string) {
  const r = resourceByKey(resource);
  const mod = modules.find((m) => m.key === r?.module);
  const item = mod?.items.find((i) => i.resource === resource);
  return `/${r?.module}/${item?.key ?? ""}?doc=${encodeURIComponent(id)}`;
}
type DocumentDetailsProps = {
  resource: Resource;
  id: string;
  onClose: () => void;
  onEdit: (r: RecordData) => void;
};
type Visit = { resource: Resource; id: string };

// The opened document is ?doc=, documents opened from it are kept in ?path= so a
// refresh or shared link reopens the same trail; Escape steps back one level.
export function DocumentDetails(props: DocumentDetailsProps) {
  const [search, setSearch] = useSearchParams();
  const navigate = useNavigate();
  const path: Visit[] = (search.get("path") ?? "")
    .split(",")
    .filter(Boolean)
    .map((part) => {
      const [key, id] = part.split(":");
      return { resource: resourceByKey(key)!, id };
    })
    .filter((v) => v.resource && v.id);
  const trail: Visit[] = [{ resource: props.resource, id: props.id }, ...path];
  const current = trail[trail.length - 1];
  const setPath = (visits: Visit[]) => {
    const next = new URLSearchParams(search);
    if (visits.length)
      next.set(
        "path",
        visits.map((v) => `${v.resource.key}:${v.id}`).join(","),
      );
    else next.delete("path");
    setSearch(next);
  };
  const openRelated = (key: string, id: string) => {
    const resource = resourceByKey(key);
    if (!resource || (key === current.resource.key && id === current.id))
      return;
    setPath([...path, { resource, id }]);
  };
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      // Dialogs and edit panels above the drawer handle their own Escape first.
      if (hasOverlayAbove() || fromOverlayAbove(event)) return;
      event.preventDefault();
      if (path.length) setPath(path.slice(0, -1));
      else props.onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  });
  return (
    <DocumentDetailView
      key={`${trail.length}/${current.resource.key}/${current.id}`}
      {...props}
      resource={current.resource}
      id={current.id}
      trail={trail}
      onJump={(index) => setPath(path.slice(0, index))}
      onRelated={openRelated}
      onNavigate={() => {
        props.onClose();
        navigate(documentUrl(current.resource.key, current.id));
      }}
    />
  );
}

function DocumentDetailView({
  resource,
  id,
  onClose,
  onEdit,
  trail,
  onJump,
  onRelated: go,
  onNavigate,
}: DocumentDetailsProps & {
  trail: Visit[];
  onJump: (index: number) => void;
  onRelated: (resource: string, id: string) => void;
  onNavigate: () => void;
}) {
  const preview = trail.length > 1;
  const { data: row, isLoading, error, refetch } = useRecord(resource.key, id);
  const receipts = useRecords(
    resource.key === "purchase-orders" ? "goods-receipts" : "",
    { size: 10000 },
  );
  const { actor } = useSession();
  const { approve, transition } = useActions(resource.key, actor);
  const [tab, setTab] = useState("summary");
  const [reject, setReject] = useState(false);
  const [reason, setReason] = useState("");
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const pending = row?.approvals.find((s) => s.status === "대기");
  const isApproval = resource.kind === "approval";
  const fieldKeys = new Set(resource.fields.map((f) => f.key));
  const flowSteps =
    resource.workflow && hasDocumentSteps(resource.workflow)
      ? resource.workflow
      : null;
  const activeActions =
    resource.transitions?.filter(
      (t) =>
        row &&
        t.from.includes(row.status) &&
        (!t.target ||
          t.target === "approvals" ||
          (t.target === "goods-receipts" &&
            !!receipts.data &&
            receiptProgress(row, receipts.data.all).some(
              (l) => l.remaining > 0,
            )) ||
          (t.target !== "goods-receipts" &&
            !row.links.some((l) => l.resource === t.target))),
    ) ?? [];
  const last = row?.history[row.history.length - 1];
  const footer = row && (
    <Group justify="space-between" wrap="nowrap">
      <span className="detail-footer-note">
        {last ? `최근 처리 · ${last.action} · ${last.actor} · ${last.at}` : ""}
      </span>
      {!preview && (
        <Group gap="xs" wrap="nowrap">
          {canEdit(resource, row, actor) && (
            <Button
              variant="default"
              leftSection={<IconPencil size={14} />}
              onClick={() => onEdit(row)}
            >
              {isApproval ? "임시저장 / 수정" : "수정"}
            </Button>
          )}
          {isApproval &&
            row.owner === actor &&
            ["작성중", "반려", "회수"].includes(row.status) && (
              <Button
                loading={approve.isPending}
                onClick={() => approve.mutate({ id, action: "상신" })}
              >
                상신
              </Button>
            )}
          {isApproval &&
            row.owner === actor &&
            ["상신", "결재중"].includes(row.status) && (
              <Button
                variant="default"
                loading={approve.isPending}
                onClick={() => approve.mutate({ id, action: "회수" })}
              >
                회수
              </Button>
            )}
          {isApproval &&
            pending?.name === actor &&
            ["상신", "결재중"].includes(row.status) && (
              <>
                <Button
                  color="red"
                  variant="light"
                  onClick={() => setReject(true)}
                >
                  반려
                </Button>
                <Button
                  loading={approve.isPending}
                  onClick={() => approve.mutate({ id, action: "승인" })}
                >
                  승인
                </Button>
              </>
            )}
          {isApproval && row.status === "승인완료" && row.links[0] && (
            <Button onClick={() => go(row.links[0].resource, row.links[0].id)}>
              관련 ERP 문서 보기
            </Button>
          )}
          {activeActions.map((a) => (
            <Button
              key={a.label}
              color={a.danger ? "red" : "neutral"}
              variant={a.danger ? "light" : "filled"}
              loading={transition.isPending}
              onClick={() => setPendingAction(a.label)}
            >
              {a.label}
            </Button>
          ))}
        </Group>
      )}
    </Group>
  );
  return (
    <>
      <DetailDrawer
        opened
        onClose={onClose}
        title={
          <div className="drawer-title">
            {preview && (
              <nav className="drawer-trail" aria-label="문서 경로">
                {trail.slice(0, -1).map((v, i) => (
                  <Fragment key={`${v.resource.key}:${v.id}`}>
                    <button type="button" onClick={() => onJump(i)}>
                      {v.id}
                    </button>
                    <IconChevronRight size={12} aria-hidden="true" />
                  </Fragment>
                ))}
              </nav>
            )}
            <span>{resource.singular}</span>
            <b>{id}</b>
          </div>
        }
        footer={footer}
      >
        {preview && (
          <Group justify="space-between" mb="md">
            <Button
              variant="subtle"
              color="neutral"
              leftSection={<IconArrowLeft size={16} />}
              onClick={() => onJump(trail.length - 2)}
            >
              {trail[trail.length - 2].id}로 돌아가기
            </Button>
            <Button
              variant="default"
              rightSection={<IconExternalLink size={15} />}
              onClick={onNavigate}
            >
              해당 업무로 이동
            </Button>
          </Group>
        )}
        {isLoading ? (
          <Loader />
        ) : error ? (
          <Alert color="red">
            {error.message}
            <Button onClick={() => refetch()}>다시 시도</Button>
          </Alert>
        ) : (
          row && (
            <>
              <div className="detail-title">
                <div className="detail-meta">
                  <StatusBadge status={row.status} />
                  <span>
                    {row.date}{" "}
                    {resource.key === "purchase-bills"
                      ? "전기일"
                      : resource.key === "goods-receipts"
                        ? "입고일"
                        : resource.key === "payments-out"
                          ? "지급일"
                          : "작성"}
                  </span>
                  {row.partner && <span>{row.partner}</span>}
                  {row.amount > 0 && (
                    <span className="money">{won(row.amount)}</span>
                  )}
                </div>
                <h2>{row.title}</h2>
              </div>
              {row.values.rejectReason && (
                <Alert color="red" mb="md" title="반려 사유">
                  {String(row.values.rejectReason)}
                </Alert>
              )}
              <Tabs
                value={tab}
                onChange={(value) => setTab(value ?? "summary")}
              >
                <Tabs.List>
                  <Tabs.Tab value="summary">상세 정보</Tabs.Tab>
                  <Tabs.Tab value="history">
                    변경 이력 ({row.history.length})
                  </Tabs.Tab>
                </Tabs.List>
                <Tabs.Panel value="summary">
                  {(flowSteps || row.links.length > 0) && (
                    <section className="detail-links">
                      <h3>
                        업무 흐름{" "}
                        <small>
                          연결 문서 {row.links.length}건 · 누르면 이 창에서 열림
                        </small>
                      </h3>
                      {flowSteps && (
                        <DocumentFlow
                          steps={flowSteps}
                          resource={resource.key}
                          links={row.links}
                          onOpen={go}
                        />
                      )}
                      {row.links.length > 0 ? (
                        <div className="related-grid">
                          {row.links.map((l) => (
                            <button
                              className="related-document"
                              key={`${l.resource}/${l.id}`}
                              onClick={() => go(l.resource, l.id)}
                            >
                              <IconFileDescription size={16} />
                              <span>
                                {resourceByKey(l.resource)?.singular}
                                <b>{l.id}</b>
                              </span>
                              <IconChevronRight size={14} />
                            </button>
                          ))}
                        </div>
                      ) : (
                        <p className="muted">
                          아직 연결된 문서가 없습니다. 업무를 진행하면 후속
                          문서가 연결됩니다.
                        </p>
                      )}
                    </section>
                  )}
                  <section>
                    <h3>기본정보</h3>
                    <dl className="detail-grid">
                      {resource.fields.map((f) => (
                        <div key={f.key}>
                          <dt>{f.label}</dt>
                          <dd>
                            {f.type === "money"
                              ? won(Number(fieldValue(row, f.key)))
                              : String(fieldValue(row, f.key)) || "—"}
                          </dd>
                        </div>
                      ))}
                      {!fieldKeys.has("owner") && (
                        <div>
                          <dt>담당자</dt>
                          <dd>{row.owner}</dd>
                        </div>
                      )}
                      {!fieldKeys.has("department") && (
                        <div>
                          <dt>부서</dt>
                          <dd>{row.department}</dd>
                        </div>
                      )}
                    </dl>
                  </section>
                  {isApproval && (
                    <section>
                      <h3>
                        결재선 <small>순차 결재</small>
                      </h3>
                      <ApprovalTimeline steps={row.approvals} />
                      {pending && ["상신", "결재중"].includes(row.status) && (
                        <p className="muted">
                          현재 결재자: {pending.name} · 상단 사용자 선택으로
                          역할을 시연할 수 있습니다.
                        </p>
                      )}
                    </section>
                  )}
                  {isPurchase(resource.key) && (
                    <PurchaseDetailLines resource={resource} row={row} />
                  )}
                  {!isPurchase(resource.key) && row.lines.length > 0 && (
                    <section>
                      <h3>
                        {resource.key === "boms" ? "구성 자재" : "품목 내역"}
                      </h3>
                      <Table>
                        <Table.Thead>
                          <Table.Tr>
                            <Table.Th>품목</Table.Th>
                            <Table.Th ta="right">수량</Table.Th>
                            <Table.Th ta="right">단가</Table.Th>
                            <Table.Th ta="right">금액</Table.Th>
                          </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                          {row.lines.map((l, i) => (
                            <Table.Tr key={i}>
                              <Table.Td>{l.item}</Table.Td>
                              <Table.Td ta="right">{l.qty}</Table.Td>
                              <Table.Td ta="right">{won(l.price)}</Table.Td>
                              <Table.Td ta="right">
                                {won(l.qty * l.price)}
                              </Table.Td>
                            </Table.Tr>
                          ))}
                        </Table.Tbody>
                      </Table>
                      <div className="detail-total">
                        공급가액 합계 <strong>{won(row.amount)}</strong>
                      </div>
                    </section>
                  )}
                  {row.journal.length > 0 && (
                    <section>
                      <h3>분개 내역</h3>
                      <Table>
                        <Table.Thead>
                          <Table.Tr>
                            <Table.Th>계정과목</Table.Th>
                            <Table.Th ta="right">차변</Table.Th>
                            <Table.Th ta="right">대변</Table.Th>
                          </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                          {row.journal.map((l, i) => (
                            <Table.Tr key={i}>
                              <Table.Td>{l.account}</Table.Td>
                              <Table.Td ta="right">{won(l.debit)}</Table.Td>
                              <Table.Td ta="right">{won(l.credit)}</Table.Td>
                            </Table.Tr>
                          ))}
                        </Table.Tbody>
                      </Table>
                    </section>
                  )}
                  <section>
                    <h3>비고</h3>
                    <p className="detail-memo">
                      {String(row.values.memo ?? "등록된 비고가 없습니다.")}
                    </p>
                  </section>
                </Tabs.Panel>
                <Tabs.Panel value="history">
                  <section className="history-list">
                    {[...row.history].reverse().map((h, i) => (
                      <div key={i}>
                        <span className="history-dot" />
                        <div>
                          <b>{h.action}</b>
                          <p>
                            {h.actor} <span>· {h.at}</span>
                          </p>
                        </div>
                      </div>
                    ))}
                  </section>
                </Tabs.Panel>
              </Tabs>
            </>
          )
        )}
      </DetailDrawer>
      <Modal
        opened={reject}
        onClose={() => setReject(false)}
        title="문서 반려"
        centered
      >
        <Textarea
          label="반려 사유"
          required
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          minRows={3}
        />
        <Group justify="flex-end" mt="lg">
          <Button variant="default" onClick={() => setReject(false)}>
            취소
          </Button>
          <Button
            color="red"
            disabled={!reason.trim()}
            loading={approve.isPending}
            onClick={() =>
              approve.mutate(
                { id, action: "반려", reason },
                { onSuccess: () => setReject(false) },
              )
            }
          >
            반려 확인
          </Button>
        </Group>
      </Modal>
      <Modal
        opened={!!pendingAction}
        onClose={() => setPendingAction(null)}
        title="업무 처리 확인"
      >
        <p>
          <b>{id}</b> 문서의 <b>{pendingAction}</b> 작업을 진행합니다.
        </p>
        <p className="muted">
          상태 변경과 후속 문서 생성 내역은 감사로그에 기록됩니다.
        </p>
        <Group justify="flex-end" mt="lg">
          <Button variant="default" onClick={() => setPendingAction(null)}>
            취소
          </Button>
          <Button
            loading={transition.isPending}
            onClick={() =>
              pendingAction &&
              transition.mutate(
                { id, action: pendingAction },
                { onSuccess: () => setPendingAction(null) },
              )
            }
          >
            처리 확인
          </Button>
        </Group>
      </Modal>
    </>
  );
}
