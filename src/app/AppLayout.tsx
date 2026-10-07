import { AssistantDrawer } from "../features/assistant/AssistantDrawer";
import { useEffect, useRef, useState } from "react";
import { useMediaQuery } from "@mantine/hooks";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import { Avatar, Button, Burger, Select, TextInput } from "@mantine/core";
import {
  IconSparkles,
  IconSearch,
  IconChevronRight,
} from "@tabler/icons-react";
import { modules } from "./catalog";
import { railItems, railKeyFor } from "./navigation";
import { PEOPLE } from "../domain/types";
import { createContext, useContext } from "react";
export const SessionContext = createContext({
  actor: "김민희",
  openAssistant: () => {},
});
export const useSession = () => useContext(SessionContext);
export function AppLayout() {
  const [mobile, setMobile] = useState(false);
  const isMobile = useMediaQuery("(max-width: 800px)", undefined, {
    getInitialValueInEffect: false,
  });
  const menuButton = useRef<HTMLButtonElement>(null);
  const sidebarOpen = !isMobile || mobile;

  useEffect(() => setMobile(false), [isMobile]);
  useEffect(() => {
    if (!isMobile || !mobile) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMobile(false);
        menuButton.current?.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [isMobile, mobile]);
  const [actor, setActor] = useState("김민희");
  const [search, setSearch] = useState("");
  const [assistant, setAssistant] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const [moduleKey, screenKey] = location.pathname.split("/").slice(1);
  const mod = modules.find((m) => m.key === moduleKey);
  const screen = mod?.items.find((i) => i.key === screenKey);
  const [railKey, setRailKey] = useState(() => railKeyFor(location.pathname));
  const rail = railItems.find((r) => r.key === railKey) ?? railItems[0];
  // Deep links, search results, and browser history reveal the destination menu.
  useEffect(() => {
    setRailKey(railKeyFor(location.pathname));
    setMobile(false);
  }, [location.pathname]);
  return (
    <SessionContext.Provider
      value={{ actor, openAssistant: () => setAssistant(true) }}
    >
      <a className="skip-link" href="#main-content">
        본문으로 건너뛰기
      </a>
      <div className="app-shell">
        <aside
          id="main-sidebar"
          className={`sidebar ${sidebarOpen ? "sidebar-open" : ""} ${isMobile && mobile ? "mobile-open" : ""}`}
          inert={!sidebarOpen}
          aria-label="업무 메뉴"
        >
          <Link to="/" className="brand" onClick={() => setMobile(false)}>
            K-Flow<span className="brand-erp">ERP</span>
          </Link>
          <div
            className="navigation-rail"
            role="group"
            aria-label="업무 모듈 선택"
          >
            {railItems.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={item.key}
                  type="button"
                  className={`rail-button ${railKey === item.key ? "active" : ""} ${railKeyFor(location.pathname) === item.key ? "current" : ""}`}
                  aria-label={item.label}
                  aria-pressed={railKey === item.key}
                  aria-controls="section-navigation"
                  title={item.label}
                  onClick={() => setRailKey(item.key)}
                >
                  <Icon size={20} stroke={1.7} aria-hidden="true" />
                  <span>{item.short}</span>
                  {item.key === "approval" && (
                    <i className="rail-dot" aria-hidden="true" />
                  )}
                </button>
              );
            })}
          </div>
          <div className="sidebar-content">
            <h2
              className="navigation-heading"
              id="section-navigation-title"
            >
              {rail.label}
            </h2>
            <nav
              id="section-navigation"
              aria-labelledby="section-navigation-title"
            >
              {rail.sections.map((section, index) => (
                <div className="nav-section" key={section.title ?? index}>
                  {section.title && (
                    <div className="nav-section-title">{section.title}</div>
                  )}
                  {section.links.map((link) => {
                    const active = location.pathname === link.to;
                    return (
                      <Link
                        key={link.to}
                        to={link.to}
                        className={`nav-link ${active ? "active" : ""}`}
                        aria-current={active ? "page" : undefined}
                        onClick={() => setMobile(false)}
                      >
                        {link.label}
                      </Link>
                    );
                  })}
                </div>
              ))}
            </nav>
            <div className="sidebar-version">
              <span className="online-dot" />
              데모 워크스페이스 <span>v1.0</span>
            </div>
          </div>
        </aside>
        {isMobile && mobile && (
          <div className="sidebar-scrim" onClick={() => setMobile(false)} />
        )}
        <div className="workspace-main">
          <header className="topbar">
            <div className="header-left">
              <Burger
                ref={menuButton}
                opened={mobile}
                onClick={() => setMobile((open) => !open)}
                size="sm"
                className="mobile-burger"
                aria-label={mobile ? "메뉴 닫기" : "메뉴 열기"}
                aria-expanded={mobile}
                aria-controls="main-sidebar"
                title={mobile ? "메뉴 닫기" : "메뉴 열기"}
              />
              <span className="header-company">
                한결 인더스트리<span>본사</span>
              </span>
              <span className="header-divider" />
              <nav className="breadcrumb" aria-label="현재 위치">
                {mod ? (
                  <>
                    <span>{mod.label}</span>
                    <IconChevronRight size={13} aria-hidden="true" />
                    <b aria-current="page">{screen?.label ?? mod.label}</b>
                  </>
                ) : (
                  <b aria-current="page">대시보드</b>
                )}
              </nav>
            </div>
            <div className="header-right">
              <TextInput
                className="global-search"
                aria-label="메뉴 검색"
                placeholder="메뉴 검색"
                leftSection={<IconSearch size={15} />}
                value={search}
                onChange={(e) => setSearch(e.currentTarget.value)}
              />
              <Button
                variant="default"
                leftSection={<IconSparkles size={15} />}
                onClick={() => setAssistant(true)}
                className="header-ai"
              >
                AI Assistant
              </Button>
              <span className="header-divider" />
              <Avatar size={28} color="neutral" radius="xl">
                {actor.slice(0, 1)}
              </Avatar>
              <Select
                aria-label="시연 사용자"
                className="user-select"
                data={PEOPLE}
                value={actor}
                onChange={(v) => setActor(v ?? "김민희")}
                allowDeselect={false}
              />
              {search && (
                <div className="search-results">
                  {modules.flatMap((m) =>
                    m.items
                      .filter((i) => (m.label + i.label).includes(search))
                      .map((i) => (
                        <button
                          key={`${m.key}/${i.key}`}
                          onClick={() => {
                            navigate(`/${m.key}/${i.key}`);
                            setSearch("");
                          }}
                        >
                          <span className="muted">{m.label}</span>
                          <IconChevronRight size={12} />
                          {i.label}
                        </button>
                      )),
                  )}
                  {!modules.some((m) =>
                    m.items.some((i) => (m.label + i.label).includes(search)),
                  ) && <p>일치하는 메뉴가 없습니다.</p>}
                </div>
              )}
            </div>
          </header>
          <main className="main-content" id="main-content" tabIndex={-1}>
            <Outlet />
          </main>
          <footer className="app-footer">
            <span>© 2026 K-Flow ERP</span>
            <span>Mock 데이터 환경 · 변경사항은 이 브라우저에 저장됩니다</span>
          </footer>
        </div>
      </div>
      <AssistantDrawer opened={assistant} onClose={() => setAssistant(false)} />
    </SessionContext.Provider>
  );
}
