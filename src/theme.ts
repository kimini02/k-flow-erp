import {
  createTheme,
  Button,
  Input,
  Modal,
  Drawer,
  Table,
} from "@mantine/core";
export const theme = createTheme({
  primaryColor: "neutral",
  primaryShade: 8,
  defaultRadius: "sm",
  fontFamily:
    '"Pretendard Variable", Pretendard, -apple-system, BlinkMacSystemFont, system-ui, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif',
  colors: {
    // Charcoal primary for buttons; meaning colors live in index.css tokens.
    neutral: [
      "#f6f7f9", "#eceef1", "#d5d9de", "#b4bac2", "#8b929b",
      "#6b7280", "#4b5563", "#374151", "#1f2328", "#111418",
    ],
    gray: [
      "#f9fafb", "#f3f4f6", "#e5e7eb", "#d1d5db", "#9ca3af",
      "#6b7280", "#4b5563", "#374151", "#1f2937", "#111827",
    ],
    accent: [
      "#eef3fc", "#dce6f9", "#b7cbf2", "#8eade9", "#6690e0",
      "#4673d6", "#2b59c3", "#2349a3", "#1d3c86", "#172f69",
    ],
  },
  headings: { fontFamily: "inherit", fontWeight: "700" },
  fontSizes: { xs: "11px", sm: "13px", md: "14px", lg: "16px", xl: "20px" },
  lineHeights: { xs: "1.45", sm: "1.5", md: "1.5", lg: "1.45", xl: "1.4" },
  components: {
    Button: Button.extend({ defaultProps: { size: "sm", fw: 600 } }),
    Input: Input.extend({ defaultProps: { size: "sm" } }),
    Modal: Modal.extend({
      defaultProps: { centered: true, padding: "xl", radius: "sm" },
    }),
    Drawer: Drawer.extend({
      defaultProps: { position: "right", padding: "xl", size: 740 },
    }),
    Table: Table.extend({
      defaultProps: {
        verticalSpacing: 8,
        horizontalSpacing: 12,
        highlightOnHover: true,
      },
    }),
  },
});
