import type { Resource } from "../domain/types";
import { approvalModule, approvalResources } from "../features/approval/config";
import {
  purchasingModule,
  purchasingResources,
} from "../features/purchasing/config";
import {
  inventoryModule,
  inventoryResources,
} from "../features/inventory/config";
import { salesModule, salesResources } from "../features/sales/config";
import {
  manufacturingModule,
  manufacturingResources,
} from "../features/manufacturing/config";
import {
  accountingModule,
  accountingResources,
} from "../features/accounting/config";
import { hrModule, hrResources } from "../features/hr/config";
import { auditModule, auditResources } from "../features/audit/config";
import { reportingModule } from "../features/reporting/config";
export const modules = [
  approvalModule,
  purchasingModule,
  inventoryModule,
  salesModule,
  manufacturingModule,
  accountingModule,
  hrModule,
  reportingModule,
  auditModule,
];
export const resources: Resource[] = [
  ...approvalResources,
  ...purchasingResources,
  ...inventoryResources,
  ...salesResources,
  ...manufacturingResources,
  ...accountingResources,
  ...hrResources,
  ...auditResources,
];
export const resourceByKey = (key: string) =>
  resources.find((r) => r.key === key);
