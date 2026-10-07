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
  primaryShade: 7,
  defaultRadius: "sm",
  fontFamily:
    '"Pretendard", "Apple SD Gothic Neo", "Noto Sans KR", -apple-system, BlinkMacSystemFont, sans-serif',
  colors: {
    // Neutral brand; warning and error colors retain their semantic meaning.
    neutral: [
      "#f7f7f7", "#eeeeee", "#dedede", "#c5c5c5", "#a3a3a3",
      "#808080", "#595959", "#303030", "#242424", "#171717",
    ],
    gray: [
      "#fafafa", "#f5f5f5", "#eeeeee", "#e0e0e0", "#bdbdbd",
      "#9e9e9e", "#757575", "#616161", "#424242", "#212121",
    ],
  },
  headings: { fontFamily: "inherit", fontWeight: "700" },
  fontSizes: { xs: "11px", sm: "13px", md: "14px", lg: "16px", xl: "20px" },
  components: {
    Button: Button.extend({ defaultProps: { size: "sm", fw: 600 } }),
    Input: Input.extend({ defaultProps: { size: "sm" } }),
    Modal: Modal.extend({
      defaultProps: { centered: true, padding: "xl", radius: "md" },
    }),
    Drawer: Drawer.extend({
      defaultProps: { position: "right", padding: "xl", size: 740 },
    }),
    Table: Table.extend({
      defaultProps: {
        verticalSpacing: 13,
        horizontalSpacing: "md",
        highlightOnHover: true,
      },
    }),
  },
});
