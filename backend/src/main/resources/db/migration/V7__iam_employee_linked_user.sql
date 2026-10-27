-- IAM-01 R2 / AN-03 AM-01 User writer. No account, Role, Credential or administrator seed.
CREATE TABLE iam_user (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    login_key varchar(64) COLLATE "C" NOT NULL,
    account_status varchar(16) NOT NULL,
    version bigint NOT NULL,
    auth_revocation_generation bigint NOT NULL DEFAULT 0,
    creation_reason varchar(500) NOT NULL,
    last_status_reason varchar(500),
    status_changed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_iam_user PRIMARY KEY (id),
    CONSTRAINT uq_iam_user_employee UNIQUE (employee_id),
    CONSTRAINT uq_iam_user_login_key UNIQUE (login_key),
    CONSTRAINT fk_iam_user_company_employee FOREIGN KEY (company_id,employee_id)
        REFERENCES hr_employee(company_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT ck_iam_user_login_key CHECK (login_key ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    CONSTRAINT ck_iam_user_status CHECK (account_status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_iam_user_version CHECK (version>=0),
    CONSTRAINT ck_iam_user_auth_revocation_generation CHECK (auth_revocation_generation>=0),
    CONSTRAINT ck_iam_user_creation_reason CHECK (
        char_length(creation_reason) BETWEEN 1 AND 500 AND creation_reason=btrim(creation_reason,' ')
        AND creation_reason !~ U&'[\0001-\001F\007F-\009F]'
        AND creation_reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$'),
    CONSTRAINT ck_iam_user_status_reason CHECK (
        (last_status_reason IS NULL AND status_changed_at IS NULL)
        OR (last_status_reason IS NOT NULL AND status_changed_at IS NOT NULL
            AND char_length(last_status_reason) BETWEEN 1 AND 500 AND last_status_reason=btrim(last_status_reason,' ')
            AND last_status_reason !~ U&'[\0001-\001F\007F-\009F]'
            AND last_status_reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$')),
    CONSTRAINT ck_iam_user_timestamps CHECK (isfinite(created_at) AND isfinite(updated_at)
        AND (status_changed_at IS NULL OR isfinite(status_changed_at)))
);
