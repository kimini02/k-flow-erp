-- IAM-03 additive grant history. Parent composite keys precede both child foreign keys.
ALTER TABLE iam_user ADD CONSTRAINT uq_iam_user_company_id UNIQUE (company_id, id) NOT DEFERRABLE;
ALTER TABLE iam_role ADD CONSTRAINT uq_iam_role_company_id UNIQUE (company_id, id) NOT DEFERRABLE;

CREATE TABLE iam_user_role (
    id UUID NOT NULL,
    company_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    grant_reason VARCHAR(500) NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(500),
    revoke_kind VARCHAR(32),
    version BIGINT NOT NULL,
    CONSTRAINT pk_iam_user_role PRIMARY KEY (id),
    CONSTRAINT fk_iam_user_role_user FOREIGN KEY (company_id, user_id) REFERENCES iam_user(company_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT fk_iam_user_role_role FOREIGN KEY (company_id, role_id) REFERENCES iam_role(company_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT ck_iam_user_role_version CHECK (version >= 0),
    CONSTRAINT ck_iam_user_role_grant_reason CHECK (char_length(grant_reason) BETWEEN 1 AND 500
        AND grant_reason = btrim(grant_reason, ' ') AND grant_reason <> ''),
    CONSTRAINT ck_iam_user_role_revocation CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL AND revoke_kind IS NULL) OR
        (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL AND revoke_kind IS NOT NULL
            AND revoke_kind IN ('MANUAL', 'ROLE_DEACTIVATION')
            AND char_length(revoke_reason) BETWEEN 1 AND 500
            AND revoke_reason = btrim(revoke_reason, ' ') AND revoke_reason <> '')),
    CONSTRAINT ck_iam_user_role_timestamps CHECK (isfinite(granted_at) AND (revoked_at IS NULL OR isfinite(revoked_at)))
);
-- This is deliberately a partial UNIQUE index, not a table UNIQUE constraint.
CREATE UNIQUE INDEX uq_iam_user_role_unrevoked ON iam_user_role(company_id, user_id, role_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_iam_user_role_user_history ON iam_user_role(company_id, user_id, granted_at DESC, id ASC);
CREATE INDEX ix_iam_user_role_role_history ON iam_user_role(company_id, role_id, granted_at DESC, id ASC);
