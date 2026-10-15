import { test, expect, type Page } from "@playwright/test";
async function select(page: Page, label: string, value: string) {
  await page.getByRole("textbox", { name: label, exact: true }).click();
  await page.getByRole("option", { name: value, exact: true }).click();
}
async function setup(page: Page) {
  await page.goto("/purchasing/orders");
  return await page.evaluate(async () => {
    // Browser-only Mock fixture: no backend or network contract.
    const store = await import("/src/api/store.ts" /* @vite-ignore */);
    const helpers = await import("/src/domain/helpers.ts" /* @vite-ignore */);
    const catalog = await import("/src/app/catalog.ts" /* @vite-ignore */);
    const model = await import(
      "/src/features/purchasing/model.ts" /* @vite-ignore */
    );
    const p = model.initializePurchase(
      "purchase-orders",
      helpers.blankRecord(catalog.resourceByKey("purchase-orders"), "김민희"),
    );
    p.partner = "한빛전자";
    p.values.warehouse = "본사 자재창고";
    p.title = "E2E 부분입고 기준";
    p.lines = [
      { item: "PCB 기판 Type-A", qty: 100, price: 1000 },
      { item: "알루미늄 하우징", qty: 100, price: 2000 },
    ];
    const saved = store.saveRecord("purchase-orders", p, "김민희");
    store.transitionRecord("purchase-orders", saved.id, "발주 확정", "김민희");
    return saved.id;
  });
}
async function confirm(page: Page, name: string) {
  await page.getByRole("button", { name, exact: true }).click();
  await page.getByRole("button", { name: "처리 확인", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "처리 확인", exact: true }),
  ).toHaveCount(0);
}

test("거래처 없는 PR 저장: 요청 필드·품목 메타정보·초안 상태", async ({
  page,
}) => {
  await page.goto("/purchasing/requests?new=1");
  await expect(page.getByLabel("요청자", { exact: true })).toHaveValue(
    "김민희",
  );
  await page
    .getByLabel("문서 제목", { exact: true })
    .fill("신규 생산 자재 요청");
  await page.getByLabel("구매 사유", { exact: true }).fill("조립용 PCB 필요");
  await select(page, "품목 1", "PCB 기판 Type-A");
  await page.getByLabel("요청수량 1", { exact: true }).fill("100");
  await expect(
    page.getByRole("columnheader", { name: "예상단가", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("부가세", { exact: true })).toHaveCount(0);
  await page.screenshot({
    path: "/private/tmp/kflow-purchase-pr.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "신규 생산 자재 요청", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "결재 상신", exact: true }),
  ).toBeVisible();
  const db = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("k-flow-erp-v1")!),
  );
  expect(db["purchase-requests"][0].partner).toBe("");
  expect(db["purchase-requests"][0].status).toBe("작성중");
});

test("PO는 거래처·창고·조건·과세·품목 기준단가를 사용하고 저장 후에도 초안", async ({
  page,
}) => {
  await page.goto("/purchasing/orders?new=1");
  await select(page, "거래처", "한빛전자");
  await select(page, "입고창고", "본사 자재창고");
  await select(page, "품목 1", "PCB 기판 Type-A");
  await expect(page.getByLabel("단가 1", { exact: true })).not.toHaveValue("0");
  await page.getByLabel("발주수량 1", { exact: true }).fill("100");
  await page.getByLabel("단가 1", { exact: true }).fill("1000");
  await expect(page.getByLabel("발주일", { exact: true })).toBeVisible();
  await expect(page.getByLabel("납기예정일", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "/private/tmp/kflow-purchase-po.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "한빛전자 구매발주", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "발주 확정", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "발주 확정", exact: true }),
  ).toBeVisible();
});

test("입고 Grid와 반복 부분입고, 매입 세액·AP·지급 배분까지 연결", async ({
  page,
}) => {
  test.setTimeout(60000);
  const poId = await setup(page);
  await page.goto("/purchasing/receipts?new=1");
  await select(page, "원천 구매발주 PO", `${poId} · 한빛전자`);
  await page.getByLabel("이번 입고수량 1", { exact: true }).fill("30");
  await page.getByLabel("이번 입고수량 2", { exact: true }).fill("0");
  await page.screenshot({
    path: "/private/tmp/kflow-purchase-receipt.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "입고 확정", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "수정", exact: true }).click();
  await expect(page.getByLabel("이번 입고수량 2", { exact: true })).toHaveValue(
    "0",
  );
  await page.getByLabel("이번 입고수량 2", { exact: true }).fill("1");
  await page.getByLabel("이번 입고수량 2", { exact: true }).fill("0");
  await page
    .getByRole("button", { name: "변경사항 저장", exact: true })
    .click();
  await expect(page.getByLabel("등록 창 닫기")).toHaveCount(0);
  let data = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("k-flow-erp-v1")!),
  );
  expect(data["purchase-orders"].find((x: any) => x.id === poId).status).toBe(
    "확정",
  );
  await confirm(page, "입고 확정");
  data = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("k-flow-erp-v1")!),
  );
  expect(data["purchase-orders"].find((x: any) => x.id === poId).status).toBe(
    "부분입고",
  );
  await page.goto("/purchasing/receipts?new=1");
  await select(page, "원천 구매발주 PO", `${poId} · 한빛전자`);
  await expect(page.getByLabel("이번 입고수량 1", { exact: true })).toHaveValue(
    "70",
  );
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "입고 확정", exact: true }),
  ).toBeVisible();
  await confirm(page, "입고 확정");
  await confirm(page, "매입 문서 생성");
  data = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("k-flow-erp-v1")!),
  );
  expect(data["purchase-orders"].find((x: any) => x.id === poId).status).toBe(
    "입고완료",
  );
  const b = data["purchase-bills"][0];
  await page.goto(`/purchasing/bills?doc=${b.id}`);
  await page.getByRole("button", { name: "수정", exact: true }).click();
  await page.getByLabel("증빙번호", { exact: true }).fill("SUPPLIER-E2E-01");
  await expect(page.getByLabel("증빙일", { exact: true })).toBeVisible();
  await expect(page.getByLabel("회계 전기일", { exact: true })).toBeVisible();
  await expect(page.getByLabel("지급기한", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: "원천 입고 문서", exact: true }),
  ).toHaveValue(/GR-2026-/);
  await page.screenshot({
    path: "/private/tmp/kflow-purchase-bill.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "변경사항 저장", exact: true })
    .click();
  await expect(page.getByLabel("등록 창 닫기")).toHaveCount(0);
  await confirm(page, "매입 전기");
  data = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("k-flow-erp-v1")!),
  );
  const ap = data.payables.find((x: any) => x.values.billId === b.id);
  expect(ap.amount).toBe(297000);
  await page.goto("/purchasing/payables");
  await expect(
    page.getByRole("button", { name: "신규 매입채무", exact: true }),
  ).toHaveCount(0);
  await page.getByText("문서 목록", { exact: true }).click();
  await expect(
    page.getByRole("columnheader", { name: "원천 매입문서", exact: true }),
  ).toBeVisible();
  await page.goto("/purchasing/payments?new=1");
  await select(page, "거래처", "한빛전자");
  await select(page, "지급 계좌", "기업은행 123-456789-01");
  await page.getByLabel("지급 적요", { exact: true }).fill("완료된 송금 기록");
  await page.getByLabel("지급액", { exact: true }).fill("60000");
  await expect(
    page.getByRole("button", { name: "저장", exact: true }),
  ).toBeDisabled();
  await page.getByLabel(`${ap.id} 이번 지급액`, { exact: true }).fill("60000");
  await expect(
    page.getByRole("button", { name: "저장", exact: true }),
  ).toBeEnabled();
  await page.screenshot({
    path: "/private/tmp/kflow-purchase-payment.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "완료된 송금 기록", exact: true }),
  ).toBeVisible();
  data = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("k-flow-erp-v1")!),
  );
  expect(data.payables.find((x: any) => x.id === ap.id).values.balance).toBe(
    237000,
  );
});

test("기존 판매 폼과 보고서 화면을 유지", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/sales/orders?new=1");
  await expect(page.getByLabel("작성일")).toBeVisible();
  await expect(page.locator(".line-form")).toHaveCount(1);
  await expect(page.getByLabel("수량 1", { exact: true })).toBeVisible();
  await page.goto("/reporting/overview");
  await expect(page.locator(".fo-metric")).toHaveCount(6);
  expect(errors).toEqual([]);
});

test("승인된 PR 선택 시 품목을 가져오고 PO 값만 수정한다", async ({ page }) => {
  await page.goto("/purchasing/requests");
  const prId = await page.evaluate(async () => {
    const store = await import("/src/api/store.ts" /* @vite-ignore */);
    const helpers = await import("/src/domain/helpers.ts" /* @vite-ignore */);
    const catalog = await import("/src/app/catalog.ts" /* @vite-ignore */);
    const p = helpers.blankRecord(
      catalog.resourceByKey("purchase-requests"),
      "김민희",
    );
    p.title = "PR 원천 테스트";
    p.values.reason = "생산 자재";
    p.partner = "한빛전자";
    p.lines = [{ item: "PCB 기판 Type-A", qty: 10, price: 1000 }];
    const saved = store.saveRecord("purchase-requests", p, "김민희");
    const submitted = store.transitionRecord(
      "purchase-requests",
      saved.id,
      "결재 상신",
      "김민희",
    );
    const id = submitted.links.find((x: any) => x.resource === "approvals").id;
    let approval = store.getRecord("approvals", id);
    for (let i = 0; i < 10 && approval.status !== "승인완료"; i++) {
      const pending = approval.approvals.find(
        (step: any) => step.status === "대기",
      );
      if (!pending) throw new Error("결재 대기자가 없습니다.");
      approval = store.approvalAction(id, "승인", pending.name);
    }
    if (approval.status !== "승인완료")
      throw new Error("결재가 완료되지 않았습니다.");
    return saved.id;
  });
  await page.goto("/purchasing/orders?new=1");
  await select(page, "원천 구매요청 PR (선택)", `${prId} · 한빛전자`);
  await expect(
    page.getByRole("textbox", { name: "거래처", exact: true }),
  ).toHaveValue("한빛전자");
  await expect(page.getByLabel("발주수량 1", { exact: true })).toHaveValue(
    "10",
  );
  await expect(page.getByLabel("단가 1", { exact: true })).toHaveValue("1,000");
  await page.getByLabel("발주수량 1", { exact: true }).fill("8");
  await page.getByLabel("단가 1", { exact: true }).fill("2000");
  await select(page, "입고창고", "본사 자재창고");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "발주 확정", exact: true }),
  ).toBeVisible();
  const db = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("k-flow-erp-v1")!),
  );
  expect(db["purchase-orders"][0].lines[0].qty).toBe(8);
  expect(db["purchase-orders"][0].lines[0].price).toBe(2000);
  expect(db["purchase-orders"][0].values.sourceId).toBe(prId);
  expect(
    db["purchase-requests"].find((x: any) => x.id === prId).lines[0].qty,
  ).toBe(10);
});
