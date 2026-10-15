import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 30000,
  fullyParallel: false,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:5173",
    viewport: { width: 1440, height: 1050 },
    launchOptions: {
      executablePath:
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
