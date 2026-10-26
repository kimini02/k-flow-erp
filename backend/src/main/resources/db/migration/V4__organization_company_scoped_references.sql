-- Organization-owned additive keys for approved HR composite references. No data or V1-V3 edits.
ALTER TABLE organization_site ADD CONSTRAINT uq_organization_site_company_id UNIQUE (company_id,id);
ALTER TABLE organization_department ADD CONSTRAINT uq_organization_department_company_id UNIQUE (company_id,id);
ALTER TABLE organization_position ADD CONSTRAINT uq_organization_position_company_id UNIQUE (company_id,id);
