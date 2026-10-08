import { useState } from "react";
import { Button, Table } from "@mantine/core";
import { IconArrowRight, IconExternalLink } from "@tabler/icons-react";
import type { RecordData } from "../../domain/types";
import { StatusBadge } from "../../components/StatusBadge";

const won = (n: number) => n.toLocaleString("ko-KR");
const categoryRank = ["완제품", "반제품", "원재료", "소모품"];
const versionKey = (r: RecordData) =>
  String(r.values.version ?? "")
    .replace(/[^0-9.]/g, "")
    .split(".")
    .map((n) => n.padStart(4, "0"))
    .join(".");

// Single-level BOM sheet: product -> components. Components that have their own
// active BOM link to it instead of exploding levels (multi-level is DR-04/18).
export function BomStructure({
  boms,
  items,
  workOrders,
  onOpen,
  selectedId,
}: {
  boms: RecordData[];
  items: RecordData[];
  workOrders: RecordData[];
  onOpen: (r: RecordData) => void;
  selectedId?: string | null;
}) {
  const meta = (title: string) => items.find((i) => i.title === title);
  const category = (title: string) =>
    String(meta(title)?.values.category ?? "—");
  const usedAsComponent = new Set(
    boms
      .filter((b) => b.status === "사용중")
      .flatMap((b) => b.lines.map((l) => l.item)),
  );
  const products = [...new Set(boms.map((b) => b.title))]
    .map((title) => {
      const versions = boms
        .filter((b) => b.title === title)
        .sort((a, b) => versionKey(b).localeCompare(versionKey(a)));
      return {
        title,
        versions,
        active: versions.find((v) => v.status === "사용중"),
      };
    })
    // Top-level products first, then sub-assemblies used inside other active BOMs.
    .sort(
      (a, b) =>
        Number(usedAsComponent.has(a.title)) -
          Number(usedAsComponent.has(b.title)) ||
        categoryRank.indexOf(category(a.title)) -
          categoryRank.indexOf(category(b.title)) ||
        a.title.localeCompare(b.title, "ko"),
    );
  const [selected, setSelected] = useState<string | null>(null);
  const product = products.find((p) => p.title === selected) ?? products[0];
  if (!product)
    return <div className="empty-state">등록된 BOM이 없습니다.</div>;
  const bom = product.active ?? product.versions[0];
  const usage = (id: string) =>
    workOrders.filter((w) => w.values.bom === id).length;
  const activeBomOf = (title: string) =>
    products.find((p) => p.title === title)?.active;
  return (
    <div className="bom-layout">
      <nav className="bom-products" aria-label="생산 품목">
        {products.map((p) => (
          <button
            key={p.title}
            type="button"
            className={p.title === product.title ? "active" : undefined}
            aria-current={p.title === product.title ? "true" : undefined}
            onClick={() => setSelected(p.title)}
          >
            <b>{p.title}</b>
            <small>
              {category(p.title)} ·{" "}
              {p.active
                ? `${p.active.values.version} 사용중`
                : "사용 버전 없음"}{" "}
              · 버전 {p.versions.length}개
            </small>
          </button>
        ))}
      </nav>
      <section className="bom-detail" aria-labelledby="bom-detail-title">
        <header>
          <div>
            <h3 id="bom-detail-title">
              {product.title} <span>{String(bom.values.version)}</span>
            </h3>
            <dl>
              <div>
                <dt>상태</dt>
                <dd>
                  <StatusBadge status={bom.status} />
                </dd>
              </div>
              <div>
                <dt>기준 생산량</dt>
                <dd>
                  {String(bom.values.outputQty ?? 1)}{" "}
                  {String(meta(product.title)?.values.unit ?? "EA")}
                </dd>
              </div>
              <div>
                <dt>로스율</dt>
                <dd>{String(bom.values.scrap ?? 0)}%</dd>
              </div>
              <div>
                <dt>자재원가</dt>
                <dd className="money">{won(bom.amount)}원</dd>
              </div>
              <div>
                <dt>사용 작업지시</dt>
                <dd>{usage(bom.id)}건</dd>
              </div>
            </dl>
          </div>
          <Button
            variant="default"
            size="xs"
            rightSection={<IconExternalLink size={13} />}
            aria-label={`${bom.id} 상세 보기`}
            onClick={() => onOpen(bom)}
          >
            BOM 문서
          </Button>
        </header>
        <div className="data-table">
          <div className="table-scroll">
            <Table miw={720} highlightOnHover={false}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th style={{ width: 48 }}>No</Table.Th>
                  <Table.Th>투입 자재</Table.Th>
                  <Table.Th style={{ width: 80 }}>유형</Table.Th>
                  <Table.Th style={{ width: 56 }}>단위</Table.Th>
                  <Table.Th data-align="end" style={{ width: 80 }}>
                    소요량
                  </Table.Th>
                  <Table.Th data-align="end" style={{ width: 110 }}>
                    단가
                  </Table.Th>
                  <Table.Th data-align="end" style={{ width: 120 }}>
                    금액
                  </Table.Th>
                  <Table.Th style={{ width: 150 }}>하위 BOM</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {bom.lines.map((line, i) => {
                  const child = activeBomOf(line.item);
                  return (
                    <Table.Tr key={`${line.item}-${i}`}>
                      <Table.Td className="muted">{i + 1}</Table.Td>
                      <Table.Td>
                        <b className="bom-item">{line.item}</b>
                      </Table.Td>
                      <Table.Td>{category(line.item)}</Table.Td>
                      <Table.Td>
                        {String(meta(line.item)?.values.unit ?? "EA")}
                      </Table.Td>
                      <Table.Td data-align="end">
                        {line.qty.toLocaleString("ko-KR")}
                      </Table.Td>
                      <Table.Td data-align="end">{won(line.price)}</Table.Td>
                      <Table.Td data-align="end">
                        {won(line.qty * line.price)}
                      </Table.Td>
                      <Table.Td>
                        {child ? (
                          <button
                            type="button"
                            className="doc-link sub-bom-link"
                            aria-label={`${line.item} 하위 BOM 보기`}
                            onClick={() => setSelected(line.item)}
                          >
                            {String(child.values.version)} 구성 보기
                            <IconArrowRight size={13} aria-hidden="true" />
                          </button>
                        ) : (
                          <span className="muted">구매 자재</span>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
              <Table.Tfoot>
                <Table.Tr className="total-row">
                  <Table.Td />
                  <Table.Td>
                    자재원가 합계{" "}
                    <small>자재 {bom.lines.length}종 · 로스율 미반영</small>
                  </Table.Td>
                  <Table.Td />
                  <Table.Td />
                  <Table.Td />
                  <Table.Td />
                  <Table.Td data-align="end">{won(bom.amount)}</Table.Td>
                  <Table.Td />
                </Table.Tr>
              </Table.Tfoot>
            </Table>
          </div>
        </div>
        <h4>버전 이력</h4>
        <div className="data-table">
          <div className="table-scroll">
            <Table miw={560} highlightOnHover={false}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th style={{ width: 150 }}>BOM 번호</Table.Th>
                  <Table.Th style={{ width: 80 }}>버전</Table.Th>
                  <Table.Th style={{ width: 110 }}>상태</Table.Th>
                  <Table.Th data-align="end">자재원가</Table.Th>
                  <Table.Th data-align="end">사용 작업지시</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {product.versions.map((v) => (
                  <Table.Tr
                    key={v.id}
                    className={`clickable-row ${selectedId === v.id ? "selected" : ""}`}
                    aria-selected={selectedId === v.id || undefined}
                    onClick={() => onOpen(v)}
                  >
                    <Table.Td>
                      <button
                        type="button"
                        className="doc-link"
                        aria-label={`${v.id} 버전 보기`}
                        onClick={(event) => {
                          event.stopPropagation();
                          onOpen(v);
                        }}
                      >
                        {v.id}
                      </button>
                    </Table.Td>
                    <Table.Td>{String(v.values.version)}</Table.Td>
                    <Table.Td>
                      <StatusBadge status={v.status} />
                    </Table.Td>
                    <Table.Td data-align="end">{won(v.amount)}</Table.Td>
                    <Table.Td data-align="end">{usage(v.id)}건</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </div>
        </div>
        <p className="bom-note">
          하위 BOM은 링크로만 연결합니다. 여러 단계를 한 번에 펼치는 방식은
          정책(DR-04/18)이 정해진 뒤 반영합니다. 작업지시는 계획 당시의 BOM
          버전을 그대로 참조합니다.
        </p>
      </section>
    </div>
  );
}
