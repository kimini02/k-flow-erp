import { Drawer } from "@mantine/core";
import type { ReactNode } from "react";
export function DetailDrawer({
  opened,
  onClose,
  title,
  children,
  footer,
}: {
  opened: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      title={title}
      closeButtonProps={{ "aria-label": "상세 닫기" }}
    >
      <div className="detail-body">{children}</div>
      {footer && <div className="detail-footer">{footer}</div>}
    </Drawer>
  );
}
