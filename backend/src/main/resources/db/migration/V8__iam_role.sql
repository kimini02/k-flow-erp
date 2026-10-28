-- IAM-02 Role-only. No UserRole, permission, grant or seed data.
CREATE TABLE iam_role (
    id UUID NOT NULL,
    company_id UUID NOT NULL,
    code VARCHAR(32) COLLATE "C" NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    creation_reason VARCHAR(500) NOT NULL,
    last_change_reason VARCHAR(500),
    last_change_kind VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_iam_role PRIMARY KEY (id),
    CONSTRAINT fk_iam_role_company FOREIGN KEY (company_id) REFERENCES organization_company(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT uq_iam_role_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_iam_role_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_iam_role_name CHECK (char_length(name) BETWEEN 1 AND 100 AND name = btrim(name, ' ') AND name <> ''),
    CONSTRAINT ck_iam_role_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_iam_role_version CHECK (version >= 0),
    CONSTRAINT ck_iam_role_creation_reason CHECK (char_length(creation_reason) BETWEEN 1 AND 500
        AND creation_reason = btrim(creation_reason, ' ') AND creation_reason <> ''),
    CONSTRAINT ck_iam_role_last_change CHECK (
        (last_change_reason IS NULL AND last_change_kind IS NULL) OR
        (last_change_reason IS NOT NULL AND last_change_kind IS NOT NULL
            AND last_change_kind IN ('RENAME', 'ACTIVATE', 'DEACTIVATE')
            AND char_length(last_change_reason) BETWEEN 1 AND 500
            AND last_change_reason = btrim(last_change_reason, ' ') AND last_change_reason <> '')),
    CONSTRAINT ck_iam_role_timestamps CHECK (isfinite(created_at) AND isfinite(updated_at))
);
