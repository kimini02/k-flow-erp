import { api } from "../../api/client";
export const approvalApi = {
  list: () => api.list("approvals", { size: 10000 }),
  act: api.approve,
};
