import { useState, useRef, useEffect } from "react";
import { Drawer, Button, Textarea, ActionIcon, Loader } from "@mantine/core";
import {
  IconSparkles,
  IconArrowUp,
  IconArrowUpRight,
  IconCheck,
  IconFileDescription,
  IconMessageCircle,
} from "@tabler/icons-react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/client";
import { won } from "../../domain/helpers";
import { useSession } from "../../app/AppLayout";
import { documentUrl } from "../../components/DocumentDetails";
import { examples, mockReply } from "./api";
import type { ChatMessage } from "./types";
export function AssistantDrawer({
  opened,
  onClose,
}: {
  opened: boolean;
  onClose: () => void;
}) {
  const { actor } = useSession();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState<string | null>(null);
  const [error, setError] = useState("");
  const qc = useQueryClient();
  const end = useRef<HTMLDivElement>(null);
  const lock = useRef(false);
  useEffect(() => {
    end.current?.scrollIntoView({ behavior: "instant" });
  }, [messages, busy]);
  async function send(text: string) {
    if (!text.trim() || lock.current) return;
    lock.current = true;
    setBusy(true);
    setInput("");
    setError("");
    setMessages((m) => [...m, { id: crypto.randomUUID(), role: "user", text }]);
    try {
      const result = await mockReply(text, actor);
      setMessages((m) => [
        ...m,
        { id: crypto.randomUUID(), role: "assistant", ...result },
      ]);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      lock.current = false;
    }
  }
  async function confirm(message: ChatMessage) {
    if (lock.current || message.resolution || !message.proposal) return;
    lock.current = true;
    setCreating(message.id);
    setError("");
    try {
      const result = await api.save(
        "purchase-requests",
        message.proposal,
        actor,
      );
      setMessages((m) =>
        m.map((x) =>
          x.id === message.id
            ? {
                ...x,
                resolution: "created",
                createdUrl: documentUrl("purchase-requests", result.id),
              }
            : x,
        ),
      );
      await qc.invalidateQueries({ queryKey: ["erp"] });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      lock.current = false;
      setCreating(null);
    }
  }
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      size={440}
      padding={0}
      title={
        <div className="assistant-title">
          <span>
            <IconSparkles size={20} />
          </span>
          <div>
            <b>K-Flow Assistant</b>
            <small>업무를 이해하는 나의 ERP 파트너</small>
          </div>
          <em>MOCK</em>
        </div>
      }
      classNames={{ body: "assistant-body", header: "assistant-header" }}
      closeButtonProps={{ "aria-label": "AI Assistant 닫기" }}
    >
      <div className="assistant-content">
        {!messages.length && (
          <div className="assistant-welcome">
            <span className="assistant-emblem">
              <IconSparkles size={30} stroke={1.3} />
            </span>
            <h2>어떤 업무를 도와드릴까요?</h2>
            <p>
              데이터 조회부터 구매요청 초안까지,
              <br />
              필요한 업무를 편하게 물어보세요.
            </p>
            <div className="assistant-examples">
              {examples.map((q) => (
                <button key={q} onClick={() => send(q)}>
                  <IconMessageCircle size={15} />
                  <span>{q}</span>
                  <IconArrowUpRight size={14} />
                </button>
              ))}
            </div>
            <div className="assistant-hint">
              <IconFileDescription size={16} />
              <span>
                업무 문서 생성은 내용을 확인한 후<br />
                직접 승인했을 때만 실행됩니다.
              </span>
            </div>
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={`chat-message ${m.role}`}>
            <div className="chat-author">
              {m.role === "assistant" ? (
                <>
                  <IconSparkles size={14} /> K-Flow Assistant
                </>
              ) : (
                actor
              )}
            </div>
            <div className="chat-bubble">{m.text}</div>
            {m.links && (
              <div className="chat-links">
                {m.links.map((l) => (
                  <Link to={l.url} key={l.url} onClick={onClose}>
                    {l.label}
                    <IconArrowUpRight size={14} />
                  </Link>
                ))}
              </div>
            )}
            {m.proposal && (
              <div className="confirmation-card">
                <div className="confirmation-heading">
                  <IconFileDescription size={17} />
                  <b>구매요청 초안</b>
                  <span>
                    {m.resolution === "created"
                      ? "생성완료"
                      : m.resolution === "cancelled"
                        ? "취소됨"
                        : "확인 필요"}
                  </span>
                </div>
                <dl>
                  <div>
                    <dt>거래처</dt>
                    <dd>{m.proposal.partner}</dd>
                  </div>
                  <div>
                    <dt>품목</dt>
                    <dd>{m.proposal.lines[0].item}</dd>
                  </div>
                  <div>
                    <dt>수량</dt>
                    <dd>{m.proposal.lines[0].qty}대</dd>
                  </div>
                  <div>
                    <dt>공급가액</dt>
                    <dd>{won(m.proposal.amount)}</dd>
                  </div>
                </dl>
                <p>작성중 문서로 저장됩니다. 결재 상신은 별도로 진행하세요.</p>
                {!m.resolution ? (
                  <div className="confirmation-actions">
                    <Button
                      variant="default"
                      size="xs"
                      disabled={!!creating}
                      onClick={() =>
                        setMessages((list) =>
                          list.map((x) =>
                            x.id === m.id
                              ? { ...x, resolution: "cancelled" }
                              : x,
                          ),
                        )
                      }
                    >
                      취소
                    </Button>
                    <Button
                      size="xs"
                      loading={creating === m.id}
                      disabled={!!creating && creating !== m.id}
                      onClick={() => confirm(m)}
                    >
                      구매요청 생성
                    </Button>
                  </div>
                ) : m.resolution === "created" ? (
                  <Link
                    className="created-link"
                    to={m.createdUrl!}
                    onClick={onClose}
                  >
                    <IconCheck size={15} /> 생성된 구매요청 보기{" "}
                    <IconArrowUpRight size={14} />
                  </Link>
                ) : (
                  <span className="muted">문서를 생성하지 않았습니다.</span>
                )}
              </div>
            )}
          </div>
        ))}
        {busy && (
          <div className="assistant-loading">
            <Loader size="xs" />
            <span>업무 데이터를 확인하고 있어요...</span>
          </div>
        )}
        {error && (
          <div className="assistant-error">
            {error}
            <Button
              size="xs"
              variant="subtle"
              color="red"
              onClick={() => setError("")}
            >
              닫기
            </Button>
          </div>
        )}
        <div ref={end} />
      </div>
      <div className="assistant-composer">
        <div>
          <Textarea
            aria-label="AI 질문"
            placeholder="업무에 대해 무엇이든 물어보세요"
            autosize
            minRows={2}
            maxRows={5}
            value={input}
            onChange={(e) => setInput(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (
                e.key === "Enter" &&
                !e.shiftKey &&
                !e.nativeEvent.isComposing
              ) {
                e.preventDefault();
                void send(input);
              }
            }}
          />
          <ActionIcon
            aria-label="질문 보내기"
            variant="filled"
            size={28}
            disabled={!input.trim() || busy}
            onClick={() => send(input)}
          >
            <IconArrowUp size={17} />
          </ActionIcon>
        </div>
        <p>시연용 Mock 응답 · 실제 AI API를 사용하지 않습니다.</p>
      </div>
    </Drawer>
  );
}
