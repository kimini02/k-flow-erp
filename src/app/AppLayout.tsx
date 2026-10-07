import { AssistantDrawer } from "../features/assistant/AssistantDrawer";
import { useEffect, useRef, useState } from "react";
import { useMediaQuery } from "@mantine/hooks";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  ActionIcon,
  Avatar,
  Button,
  Burger,
  Select,
  TextInput,
} from "@mantine/core";
import {
  IconLayoutDashboard,
  IconFileCheck,
  IconShoppingCart,
  IconBox,
  IconTruckDelivery,
  IconBuildingFactory2,
  IconBook2,
  IconUsers,
  IconChartBar,
  IconShieldCheck,
  IconSparkles,
  IconChevronDown,
  IconSearch,
  IconArrowRight,
  IconHelpCircle,
  IconBriefcase,
} from "@tabler/icons-react";
import { modules } from "./catalog";
import { PEOPLE } from "../domain/types";
import { createContext, useContext } from "react";
const icons: Record<string, typeof IconBox> = {
  approval: IconFileCheck,
  purchasing: IconShoppingCart,
  inventory: IconBox,
  sales: IconTruckDelivery,
  manufacturing: IconBuildingFactory2,
  accounting: IconBook2,
  hr: IconUsers,
  reporting: IconChartBar,
  audit: IconShieldCheck,
};
const navigationGroups = [
  { key: "workspace", label: "홈", icon: IconLayoutDashboard, modules: ["approval"] },
  { key: "business", label: "업무 관리", icon: IconBriefcase, modules: ["purchasing", "inventory", "sales", "manufacturing", "accounting"] },
  { key: "management", label: "경영 관리", icon: IconChartBar, modules: ["hr", "reporting", "audit"] },
];
const navigationGroupFor = (moduleKey: string) =>
  navigationGroups.find((section) => section.modules.includes(moduleKey)) ?? navigationGroups[0];
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
  const [expanded, setExpanded] = useState("");
  const [actor, setActor] = useState("김민희");
  const [search, setSearch] = useState("");
  const [assistant, setAssistant] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const activeModule = location.pathname.split("/")[1];
  const group = modules.find((m) => m.key === activeModule);
  const [navigationGroup, setNavigationGroup] = useState(() => navigationGroupFor(activeModule).key);
  const selectedNavigation = navigationGroups.find((section) => section.key === navigationGroup)!;
  // Deep links, search results, and browser history reveal the destination menu.
  useEffect(() => {
    setNavigationGroup(navigationGroupFor(location.pathname.split("/")[1]).key);
    setExpanded("");
    setMobile(false);
  }, [location.pathname]);
  return (
    <SessionContext.Provider
      value={{ actor, openAssistant: () => setAssistant(true) }}
    >
      <div className="app-shell">
        <aside
          id="main-sidebar"
          className={`sidebar ${sidebarOpen ? "sidebar-open" : ""} ${isMobile && mobile ? "mobile-open" : ""}`}
          inert={!sidebarOpen}
          aria-label="업무 메뉴"
        >
          <div className="sidebar-identity">
          <Link to="/" className="brand" onClick={() => {
            setNavigationGroup("workspace");
            setMobile(false);
          }}>
            <span>
              K-Flow<span className="brand-erp"> ERP</span>
              <small>업무의 흐름을 하나로</small>
            </span>
          </Link>
          <div className="workspace">
            <div>
              <b>한결 인더스트리</b>
              <small>본사 · 통합 워크스페이스</small>
            </div>
          </div>
          </div>
          <div className="navigation-rail" role="group" aria-label="업무 그룹 선택">
            {navigationGroups.map((section) => {
              const Icon = section.icon;
              return (
                <button
                  key={section.key}
                  type="button"
                  className={`navigation-group-button ${navigationGroup === section.key ? "active" : ""}`}
                  aria-label={section.label}
                  aria-pressed={navigationGroup === section.key}
                  aria-controls="section-navigation"
                  title={section.label}
                  onClick={() => setNavigationGroup(section.key)}
                >
                  <Icon size={21} stroke={1.6} aria-hidden="true" />
                </button>
              );
            })}
          </div>
          <div className="sidebar-content">
          <h2 className="navigation-heading" id="section-navigation-title">{selectedNavigation.label}</h2>
          <nav id="section-navigation" aria-labelledby="section-navigation-title">
            {navigationGroup === "workspace" && <Link
              to="/"
              className={`nav-item ${location.pathname === "/" ? "active" : ""}`}
              onClick={() => setMobile(false)}
            >
              <IconLayoutDashboard size={19} />
              <span>대시보드</span>
            </Link>}
            {modules.filter((m) => selectedNavigation.modules.includes(m.key)).map((m) => {
              const Icon = icons[m.key] ?? IconBox;
              const open =
                expanded === m.key || (!expanded && activeModule === m.key);
              return (
                <div key={m.key}>
                  <div
                    className={`nav-group ${activeModule === m.key ? "selected" : ""}`}
                  >
                    <Link
                      className="nav-item"
                      to={`/${m.key}/${m.items[0]?.key ?? ""}`}
                      onClick={() => {
                        setExpanded(m.key);
                        setMobile(false);
                      }}
                    >
                      <Icon size={19} />
                      <span>{m.label}</span>
                      {m.key === "approval" && <span className="nav-dot" />}
                    </Link>
                    {m.items.length > 1 && (
                      <button
                        className="nav-expand"
                        aria-label={`${m.label} 메뉴 펼치기`}
                        aria-expanded={open}
                        onClick={() => setExpanded(open ? "collapsed" : m.key)}
                      >
                        <IconChevronDown
                          size={14}
                          style={{
                            transform: open ? "rotate(180deg)" : undefined,
                          }}
                        />
                      </button>
                    )}
                  </div>
                  {open && m.items.length > 1 && (
                    <div className="sub-nav">
                      {m.items.map((item) => (
                        <Link
                          key={item.key}
                          to={`/${m.key}/${item.key}`}
                          className={
                            location.pathname === `/${m.key}/${item.key}`
                              ? "active"
                              : ""
                          }
                          onClick={() => setMobile(false)}
                        >
                          {item.label}
                        </Link>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </nav>
          <div className="sidebar-bottom">
            <button
              className="assistant-nav"
              onClick={() => setAssistant(true)}
            >
              <IconSparkles size={19} />
              <span>
                AI ERP Assistant<small>필요한 업무를 더 빠르게</small>
              </span>
              <IconArrowRight size={16} />
            </button>
            <div className="sidebar-version">
              <span className="online-dot" />
              데모 워크스페이스 <span>v1.0</span>
            </div>
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
              <span className="header-company">한결 인더스트리</span>
              <span className="header-divider" />
              <span className="muted">{group?.label ?? "대시보드"}</span>
            </div>
            <div className="header-right">
              <TextInput
                className="global-search"
                aria-label="메뉴 검색"
                placeholder="메뉴 검색..."
                leftSection={<IconSearch size={15} />}
                value={search}
                onChange={(e) => setSearch(e.currentTarget.value)}
              />
              <Button
                variant="light"
                leftSection={<IconSparkles size={15} />}
                onClick={() => setAssistant(true)}
                className="header-ai"
              >
                AI Assistant
              </Button>
              <span className="header-divider" />
              <Avatar size={30} color="neutral" radius="xl">
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
            </div>
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
                        {m.label} <IconChevronDown size={12} /> {i.label}
                      </button>
                    )),
                )}
                {!modules.some((m) =>
                  m.items.some((i) => (m.label + i.label).includes(search)),
                ) && <p>일치하는 메뉴가 없습니다.</p>}
              </div>
            )}
          </header>
          <main className="main-content">
            <Outlet />
          </main>
          <footer className="app-footer">
            <span>© 2026 K-Flow ERP</span>
            <span>Mock 데이터 환경 · 변경사항은 이 브라우저에 저장됩니다</span>
            <IconHelpCircle size={14} />
          </footer>
        </div>
      </div>
      <AssistantDrawer opened={assistant} onClose={() => setAssistant(false)} />
    </SessionContext.Provider>
  );
}
