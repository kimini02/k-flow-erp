CREATE TABLE organization_department (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    code varchar(32) COLLATE "C" NOT NULL CONSTRAINT ck_organization_department_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name varchar(100) NOT NULL CONSTRAINT ck_organization_department_name CHECK (char_length(name) BETWEEN 1 AND 100 AND name = btrim(name, ' ') AND name <> ''),
    status varchar(16) NOT NULL CONSTRAINT ck_organization_department_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL CONSTRAINT ck_organization_department_version CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_organization_department_company FOREIGN KEY (company_id) REFERENCES organization_company(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uq_organization_department_company_code UNIQUE (company_id, code)
);
CREATE TABLE organization_position (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    code varchar(32) COLLATE "C" NOT NULL CONSTRAINT ck_organization_position_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name varchar(100) NOT NULL CONSTRAINT ck_organization_position_name CHECK (char_length(name) BETWEEN 1 AND 100 AND name = btrim(name, ' ') AND name <> ''),
    status varchar(16) NOT NULL CONSTRAINT ck_organization_position_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL CONSTRAINT ck_organization_position_version CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_organization_position_company FOREIGN KEY (company_id) REFERENCES organization_company(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uq_organization_position_company_code UNIQUE (company_id, code)
);
