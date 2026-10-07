import React from "react";
import ReactDOM from "react-dom/client";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, Link } from "react-router-dom";
import "@mantine/core/styles.css";
import "@mantine/notifications/styles.css";
import "./index.css";
import { theme } from "./theme";
import { queryClient } from "./app/queryClient";
import { AppLayout } from "./app/AppLayout";
import { DashboardPage } from "./pages/DashboardPage";
import { DocumentListPage } from "./pages/DocumentListPage";
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <MantineProvider theme={theme}>
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <Routes>
            <Route element={<AppLayout />}>
              <Route index element={<DashboardPage />} />
              <Route path=":module/:screen" element={<DocumentListPage />} />
              <Route
                path="*"
                element={
                  <div>
                    <h1>페이지를 찾을 수 없습니다</h1>
                    <Link to="/">대시보드로 이동</Link>
                  </div>
                }
              />
            </Route>
          </Routes>
        </BrowserRouter>
      </QueryClientProvider>
    </MantineProvider>
  </React.StrictMode>,
);
