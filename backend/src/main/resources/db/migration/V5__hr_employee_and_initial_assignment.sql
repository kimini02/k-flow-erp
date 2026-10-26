-- HR-01/02. Failure to install the GiST extension must fail deployment, not weaken overlap protection.
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE hr_employee (
    id uuid CONSTRAINT pk_hr_employee PRIMARY KEY,
    company_id uuid NOT NULL,
    employee_no varchar(32) COLLATE "C" NOT NULL,
    display_name varchar(100) NOT NULL,
    employment_status varchar(16) NOT NULL,
    joined_on date NOT NULL,
    termination_effective_on date,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_hr_employee_company FOREIGN KEY (company_id) REFERENCES organization_company(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uq_hr_employee_company_id UNIQUE(company_id,id),
    CONSTRAINT uq_hr_employee_company_employee_no UNIQUE(company_id,employee_no),
    CONSTRAINT ck_hr_employee_no CHECK(employee_no ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_hr_employee_name CHECK(char_length(display_name) BETWEEN 1 AND 100 AND display_name=btrim(display_name,' ')
        AND display_name !~ U&'[\0001-\001F\007F-\009F]'
        AND display_name !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$'),
    CONSTRAINT ck_hr_employee_status CHECK(employment_status IN ('EMPLOYED','ON_LEAVE','TERMINATED')),
    CONSTRAINT ck_hr_employee_employment_dates CHECK(isfinite(joined_on) AND joined_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31' AND (
        (employment_status IN ('EMPLOYED','ON_LEAVE') AND termination_effective_on IS NULL)
        OR (employment_status='TERMINATED' AND termination_effective_on IS NOT NULL AND isfinite(termination_effective_on)
            AND termination_effective_on>joined_on AND termination_effective_on<=DATE '9999-12-31'))),
    CONSTRAINT ck_hr_employee_version CHECK(version>=0)
);
CREATE TABLE hr_employee_assignment (
    id uuid CONSTRAINT pk_hr_employee_assignment PRIMARY KEY,
    company_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    site_id uuid NOT NULL,
    department_id uuid NOT NULL,
    position_id uuid NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    reason varchar(500) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_hr_assignment_company FOREIGN KEY(company_id) REFERENCES organization_company(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hr_assignment_employee_company FOREIGN KEY(company_id,employee_id) REFERENCES hr_employee(company_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hr_assignment_site_company FOREIGN KEY(company_id,site_id) REFERENCES organization_site(company_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hr_assignment_department_company FOREIGN KEY(company_id,department_id) REFERENCES organization_department(company_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hr_assignment_position_company FOREIGN KEY(company_id,position_id) REFERENCES organization_position(company_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_hr_assignment_period CHECK(isfinite(effective_from) AND effective_from BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
        AND (effective_to IS NULL OR (isfinite(effective_to) AND effective_from<effective_to AND effective_to<=DATE '9999-12-31'))),
    CONSTRAINT ck_hr_assignment_reason CHECK(char_length(reason) BETWEEN 1 AND 500 AND reason=btrim(reason,' ')
        AND reason !~ U&'[\0001-\001F\007F-\009F]'
        AND reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$'),
    CONSTRAINT ck_hr_assignment_version CHECK(version>=0),
    CONSTRAINT ex_hr_assignment_employee_period EXCLUDE USING gist
        (employee_id WITH =, daterange(effective_from,effective_to,'[)') WITH &&) NOT DEFERRABLE
);
CREATE INDEX ix_hr_assignment_history ON hr_employee_assignment(employee_id,effective_from DESC,id ASC);
