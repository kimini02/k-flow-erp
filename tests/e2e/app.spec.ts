import { test, expect } from "@playwright/test";
import { modules } from "../../src/app/catalog";
test("모든 메뉴 렌더링, 데이터, 콘솔 오류 확인", async ({ page }) => {
  test.setTimeout(90000);
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  for (const mod of modules) {
    for (const item of mod.items) {
      await page.goto(`/${mod.key}/${item.key}`);
      await expect(
        page.getByRole("heading", { level: 1, name: item.label, exact: true }),
      ).toBeVisible();
      await expect(
        page.locator("main table tbody tr, main .statement-total").first(),
      ).toBeVisible();
      await expect(page.getByText("페이지를 찾을 수 없습니다")).toHaveCount(0);
    }
  }
  expect(errors).toEqual([]);
});
test("대시보드 시각 검증", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "대시보드", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".revenue-chart svg").first()).toBeVisible();
  await page.screenshot({
    path: "docs/screenshots/dashboard.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "AI Assistant", exact: true }).click();
  await expect(page.getByText("어떤 업무를 도와드릴까요?")).toBeVisible();
  await page.screenshot({
    path: "docs/screenshots/assistant.png",
    fullPage: true,
  });
});
test("검색, 상태 필터, 페이지 이동, 상세, 신규 발주, 저장 유지", async ({
  page,
}) => {
  await page.goto("/purchasing/orders");
  await expect(page.locator("tbody tr")).toHaveCount(10);
  await page.getByRole("button", { name: "2", exact: true }).click();
  await expect(page.locator("tbody tr").first()).toContainText("PO-2026-0011");
  await page.getByLabel("목록 검색").fill("없을검색어");
  await expect(page.getByText("조회된 데이터가 없습니다")).toBeVisible();
  await page
    .getByRole("button", { name: "필터 초기화", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "PO-2026-0001 상세 보기" }).click();
  await expect(page.getByRole("tab", { name: "상세 정보" })).toBeVisible();
  await page.screenshot({
    path: "/private/tmp/kflow-purchase-detail.png",
    fullPage: true,
  });
  await page.getByLabel("상세 닫기").click();
  await page.getByRole("button", { name: "신규 구매발주" }).click();
  await page.getByLabel("문서 제목").fill("브라우저 테스트 발주");
  await page.getByLabel("거래처", { exact: true }).click();
  await page.getByRole("option", { name: "한빛전자", exact: true }).click();
  await page.getByRole("textbox", { name: "품목 1", exact: true }).click();
  await page
    .getByRole("option", { name: "PCB 기판 Type-A", exact: true })
    .click();
  await page.getByLabel("발주수량 1", { exact: true }).fill("5");
  await page.getByRole("textbox", { name: "입고창고", exact: true }).click();
  await page
    .getByRole("option", { name: "본사 자재창고", exact: true })
    .click();
  await page.getByLabel("단가 1", { exact: true }).fill("45000");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "브라우저 테스트 발주", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "브라우저 테스트 발주", exact: true }),
  ).toBeVisible();
});
test("결재자 전환과 승인, 반려사유", async ({ page }) => {
  await page.goto("/approval/inbox?doc=APV-2026-0001");
  await expect(
    page.getByText("현재 결재자: 이회계팀장", { exact: false }),
  ).toBeVisible();
  await page.screenshot({
    path: "docs/screenshots/approval.png",
    fullPage: true,
  });
  await page.getByLabel("상세 닫기").click();
  await page.getByRole("textbox", { name: "시연 사용자" }).click();
  await page.getByRole("option", { name: "이회계팀장", exact: true }).click();
  await page.getByRole("button", { name: "APV-2026-0001 상세 보기" }).click();
  await page.getByRole("button", { name: "승인", exact: true }).click();
  await expect(
    page.getByText("현재 결재자: 최이사", { exact: false }),
  ).toBeVisible();
});
test("AI는 확인 전 생성하지 않고 취소 및 중복 생성 방지", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "AI Assistant", exact: true }).click();
  const question = "ABC전자 맥북 10대 구매요청 초안 만들어줘";
  await page.getByRole("button", { name: question, exact: true }).click();
  await expect(page.getByText("구매요청 초안", { exact: true })).toBeVisible();
  const before = await page.evaluate(
    () =>
      JSON.parse(localStorage.getItem("k-flow-erp-v1") ?? "null")?.[
        "purchase-requests"
      ]?.length ?? 24,
  );
  await page.getByRole("button", { name: "취소", exact: true }).click();
  await expect(page.getByText("문서를 생성하지 않았습니다.")).toBeVisible();
  await page.getByLabel("AI 질문").fill(question);
  await page.getByLabel("질문 보내기").click();
  await page
    .getByRole("button", { name: "구매요청 생성", exact: true })
    .click();
  await expect(
    page.getByRole("link", { name: "생성된 구매요청 보기" }),
  ).toBeVisible();
  const after = await page.evaluate(
    () =>
      JSON.parse(localStorage.getItem("k-flow-erp-v1")!)["purchase-requests"]
        .length,
  );
  expect(after).toBe(before + 1);
  await page.getByRole("link", { name: "생성된 구매요청 보기" }).click();
  await expect(
    page.getByRole("heading", { name: "개발팀 맥북 Pro 14 10대 구매요청" }),
  ).toBeVisible();
});
test("로딩 실패 후 재시도", async ({ page }) => {
  await page.goto("/");
  await page.evaluate(() => sessionStorage.setItem("kflow-network-error", "1"));
  await page.goto("/inventory/stock");
  await expect(page.getByText("데이터를 불러오지 못했습니다")).toBeVisible();
  await page.evaluate(() => sessionStorage.removeItem("kflow-network-error"));
  await page.getByRole("button", { name: "다시 시도", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(10);
});
test("모바일 레이아웃과 메뉴", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "대시보드", exact: true }),
  ).toBeVisible();
  await page.getByLabel("메뉴 열기").click();
  await expect(page.locator(".sidebar.mobile-open")).toBeVisible();
  await page
    .locator(".sidebar nav")
    .getByText("구매관리", { exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "구매요청(PR)", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "PR-2026-0001 상세 보기" }),
  ).toBeVisible();
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  expect(overflow).toBe(false);
  await page.screenshot({
    path: "docs/screenshots/mobile.png",
    fullPage: true,
  });
});

test("지급 금액 배분과 전표 연결", async ({ page }) => {
  await page.goto("/purchasing/payments");
  await page.getByRole("button", { name: "신규 지급", exact: true }).click();
  await page.getByLabel("지급 적요").fill("브라우저 지급 검증");
  await page.getByRole("textbox", { name: "거래처", exact: true }).click();
  await page.getByRole("option", { name: "한빛전자", exact: true }).click();
  await page.getByRole("textbox", { name: "지급 계좌", exact: true }).click();
  await page
    .getByRole("option", { name: "기업은행 123-456789-01", exact: true })
    .click();
  await page.getByLabel("지급액", { exact: true }).fill("10000");
  await page.locator('input[aria-label$=" 이번 지급액"]').first().fill("10000");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "브라우저 지급 검증", exact: true }),
  ).toBeVisible();
  await expect(
    page.locator(".related-document").filter({ hasText: "JE-2026" }).first(),
  ).toBeVisible();
});
