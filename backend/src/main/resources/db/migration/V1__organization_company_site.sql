CREATE TABLE organization_company (
    id uuid PRIMARY KEY,
    singleton_key smallint NOT NULL CONSTRAINT ck_organization_company_slot CHECK (singleton_key = 1),
    code varchar(32) COLLATE "C" NOT NULL CONSTRAINT ck_organization_company_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name varchar(100) NOT NULL CONSTRAINT ck_organization_company_name CHECK (char_length(name) BETWEEN 1 AND 100 AND name = btrim(name, ' ') AND name <> ''),
    version bigint NOT NULL CONSTRAINT ck_organization_company_version CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_organization_company_slot UNIQUE (singleton_key)
);
CREATE TABLE organization_site (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    code varchar(32) COLLATE "C" NOT NULL CONSTRAINT ck_organization_site_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    name varchar(100) NOT NULL CONSTRAINT ck_organization_site_name CHECK (char_length(name) BETWEEN 1 AND 100 AND name = btrim(name, ' ') AND name <> ''),
    status varchar(16) NOT NULL CONSTRAINT ck_organization_site_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL CONSTRAINT ck_organization_site_version CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_organization_site_company FOREIGN KEY (company_id) REFERENCES organization_company(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uq_organization_site_company_code UNIQUE (company_id, code)
);
INSERT INTO organization_company (id, singleton_key, code, name, version, created_at, updated_at)
VALUES ('c04b2a3e-6c22-43a7-84f1-d7df625cb826', 1, 'HANGYEOL', '한결 인더스트리', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
