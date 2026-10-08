import { Drawer } from "@mantine/core";
import { useMediaQuery } from "@mantine/hooks";
import type { ReactNode } from "react";
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
