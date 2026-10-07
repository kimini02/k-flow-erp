import { PurchaseDetailLines } from "../features/purchasing/PurchaseDetailLines";
import { isPurchase, receiptProgress } from "../features/purchasing/model";
import { useRecords } from "../api/hooks";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
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
  IconArrowRight,
  IconArrowLeft,
  IconFileDescription,
} from "@tabler/icons-react";
import type { RecordData, Resource } from "../domain/types";
import { fieldValue, won } from "../domain/helpers";
import { useRecord, useActions } from "../api/hooks";
import { useSession } from "../app/AppLayout";
import { modules, resourceByKey } from "../app/catalog";
import { canEdit } from "../api/store";
import { DetailDrawer } from "./DetailDrawer";
import { StatusBadge } from "./StatusBadge";
import { ApprovalTimeline } from "./ApprovalTimeline";
import { DocumentFlow } from "./DocumentFlow";
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
type DocumentVisit = { resource: Resource; id: string; tab: string };

export function DocumentDetails(props: DocumentDetailsProps) {
  return (
    <DocumentPreviewSession
      key={`${props.resource.key}/${props.id}`}
      {...props}
    />
  );
}

function DocumentPreviewSession(props: DocumentDetailsProps) {
  const [history, setHistory] = useState<DocumentVisit[]>([
    { resource: props.resource, id: props.id, tab: "summary" },
  ]);
  const current = history[history.length - 1];
  const navigate = useNavigate();
  const openRelated = (key: string, id: string) => {
    const resource = resourceByKey(key);
    if (!resource || (key === current.resource.key && id === current.id))
      return;
    setHistory((visits) => [...visits, { resource, id, tab: "summary" }]);
  };
  return (
    <DocumentDetailView
      key={`${history.length}/${current.resource.key}/${current.id}`}
      {...props}
      resource={current.resource}
      id={current.id}
      preview={history.length > 1}
      tab={current.tab}
      onTabChange={(tab) =>
        setHistory((visits) =>
          visits.map((visit, index) =>
            index === visits.length - 1 ? { ...visit, tab } : visit,
          ),
        )
      }
      onBack={() => setHistory((visits) => visits.slice(0, -1))}
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
  preview,
  tab,
  onTabChange,
  onBack,
  onRelated: go,
  onNavigate,
}: DocumentDetailsProps & {
  preview: boolean;
  tab: string;
  onTabChange: (tab: string) => void;
  onBack: () => void;
  onRelated: (resource: string, id: string) => void;
  onNavigate: () => void;
}) {
  const { data: row, isLoading, error, refetch } = useRecord(resource.key, id);
  const receipts = useRecords(
    resource.key === "purchase-orders" ? "goods-receipts" : "",
    { size: 10000 },
  );
  const { actor } = useSession();
  const { approve, transition } = useActions(resource.key, actor);
  const [reject, setReject] = useState(false);
  const [reason, setReason] = useState("");
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const pending = row?.approvals.find((s) => s.status === "대기");
  const isApproval = resource.kind === "approval";
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
  const footer = row && (
    <Group justify="space-between">
      <span className="muted" style={{ fontSize: 11 }}>
        담당자 · {row.owner}
      </span>
      {!preview && (
        <Group gap="xs">
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
            <span>{resource.singular}</span>
            <b>{id}</b>
          </div>
        }
        footer={footer}
      >
        {preview && (
          <Group justify="space-between" mb="lg">
            <Button
              variant="subtle"
              color="neutral"
              leftSection={<IconArrowLeft size={16} />}
              onClick={onBack}
            >
              이전 문서
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
                <StatusBadge status={row.status} />
                <span className="muted">
                  {row.date}{" "}
                  {resource.key === "purchase-bills"
                    ? "전기일"
                    : resource.key === "goods-receipts"
                      ? "입고일"
                      : resource.key === "payments-out"
                        ? "지급일"
                        : "작성"}
                </span>
                <h2>{row.title}</h2>
              </div>
              {row.values.rejectReason && (
                <Alert color="red" mb="md" title="반려 사유">
                  {String(row.values.rejectReason)}
                </Alert>
              )}
              <Tabs
                value={tab}
                onChange={(value) => onTabChange(value ?? "summary")}
              >
                <Tabs.List>
                  <Tabs.Tab value="summary">상세 정보</Tabs.Tab>
                  <Tabs.Tab value="history">
                    변경 이력 ({row.history.length})
                  </Tabs.Tab>
                  <Tabs.Tab value="related">
                    관련 문서 ({row.links.length})
                  </Tabs.Tab>
                </Tabs.List>
                <Tabs.Panel value="summary">
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
                      <div>
                        <dt>담당자</dt>
                        <dd>{row.owner}</dd>
                      </div>
                      <div>
                        <dt>부서</dt>
                        <dd>{row.department}</dd>
                      </div>
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
                  {resource.workflow && (
                    <section>
                      <h3>업무 진행현황</h3>
                      <DocumentFlow
                        steps={resource.workflow}
                        current={Math.min(
                          resource.workflow.length - 1,
                          Math.max(
                            0,
                            (
                              {
                                "purchase-requests": 0,
                                "purchase-orders": 2,
                                "goods-receipts": 3,
                                "purchase-bills": 4,
                                payables: 4,
                                "payments-out": 5,
                                quotations: 0,
                                "sales-orders": 1,
                                deliveries: 2,
                                invoices: 3,
                                receivables: 3,
                                "payments-in": 4,
                                boms: 0,
                                "work-orders": row.status === "생산중" ? 3 : 1,
                                "material-issues": 2,
                                "production-receipts": 4,
                              } as Record<string, number>
                            )[resource.key] ??
                              resource.statuses.indexOf(row.status),
                          ),
                        )}
                      />
                    </section>
                  )}
                  <section>
                    <h3>관련 문서</h3>
                    {row.links.length ? (
                      row.links.map((l) => (
                        <button
                          className="related-document"
                          key={`${l.resource}/${l.id}`}
                          onClick={() => go(l.resource, l.id)}
                        >
                          <IconFileDescription size={18} />
                          <span>
                            {resourceByKey(l.resource)?.singular}
                            <b>{l.id}</b>
                          </span>
                          <IconArrowRight size={15} />
                        </button>
                      ))
                    ) : (
                      <p className="muted">
                        아직 연결된 문서가 없습니다. 업무 진행 시 후속 문서가
                        연결됩니다.
                      </p>
                    )}
                  </section>
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
                <Tabs.Panel value="related">
                  <section>
                    {row.links.map((l) => (
                      <button
                        key={`${l.resource}/${l.id}`}
                        className="related-document"
                        onClick={() => go(l.resource, l.id)}
                      >
                        <IconFileDescription size={18} />
                        <span>
                          {resourceByKey(l.resource)?.label}
                          <b>{l.id}</b>
                        </span>
                        <IconArrowRight size={17} />
                      </button>
                    ))}
                    {!row.links.length && (
                      <p className="muted">연결된 문서가 없습니다.</p>
                    )}
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
