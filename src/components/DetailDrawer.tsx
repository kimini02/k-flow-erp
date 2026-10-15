import { Drawer } from "@mantine/core";
import { useMediaQuery } from "@mantine/hooks";
import type { ReactNode } from "react";
// True when a panel, dialog or assistant is open above the detail drawer; global
// keys (Escape, arrows) then belong to that layer, not to the drawer or the list.
export const hasOverlayAbove = () =>
  [
    ...document.querySelectorAll(
      ".mantine-Drawer-content, .mantine-Modal-content",
    ),
  ].some((el) => !el.closest(".detail-drawer"));
// The key came from inside another layer (it may already have closed itself).
export const fromOverlayAbove = (event: KeyboardEvent) => {
  const target = event.target as Element | null;
  return (
    !!target?.closest?.(".mantine-Drawer-content, .mantine-Modal-content") &&
    !target.closest(".detail-drawer")
  );
};
// Side panel that leaves the list visible and usable on wide screens.
export function DetailDrawer({
  opened,
  onClose,
  title,
  children,
  footer,
  size = 720,
  label = "상세 닫기",
}: {
  opened: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  size?: number;
  label?: string;
}) {
  const narrow = useMediaQuery("(max-width: 1000px)", undefined, {
    getInitialValueInEffect: false,
  });
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      title={title}
      size={narrow ? "100%" : `min(${size}px, 58vw)`}
      withOverlay={narrow}
      trapFocus={narrow}
      lockScroll={narrow}
      closeOnEscape={false}
      className="detail-drawer"
      closeButtonProps={{ "aria-label": label }}
    >
      <div className="detail-body">{children}</div>
      {footer && <div className="detail-footer">{footer}</div>}
    </Drawer>
  );
}
