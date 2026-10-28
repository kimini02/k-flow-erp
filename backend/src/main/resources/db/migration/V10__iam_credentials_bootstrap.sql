-- AN-01 R3. No User/Role/password seed, Session, or authorization schema.
CREATE TABLE iam_credential_enrollment (
 id uuid NOT NULL, company_id uuid NOT NULL, user_id uuid NOT NULL,
 purpose varchar(16) NOT NULL, secret_digest bytea NOT NULL, base_credential_version bigint,
 issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, consumed_at timestamptz, revoked_at timestamptz,
 revoke_reason varchar(500), issue_reason varchar(500) NOT NULL, issuer_subject varchar(200) NOT NULL, approval_ref varchar(200) NOT NULL,
 version bigint NOT NULL,
 CONSTRAINT pk_iam_credential_enrollment PRIMARY KEY(id),
 CONSTRAINT uq_iam_credential_enrollment_company_user_id UNIQUE(company_id,user_id,id),
 CONSTRAINT fk_iam_credential_enrollment_user FOREIGN KEY(company_id,user_id) REFERENCES iam_user(company_id,id) ON UPDATE NO ACTION ON DELETE RESTRICT NOT DEFERRABLE,
 CONSTRAINT ck_iam_credential_enrollment_purpose CHECK(purpose IN ('INITIAL','RESET')),
 CONSTRAINT ck_iam_credential_enrollment_digest CHECK(octet_length(secret_digest)=32),
 CONSTRAINT ck_iam_credential_enrollment_base_version CHECK((purpose='INITIAL' AND base_credential_version IS NULL) OR (purpose='RESET' AND base_credential_version IS NOT NULL AND base_credential_version>=0)),
 CONSTRAINT ck_iam_credential_enrollment_terminal CHECK(NOT(consumed_at IS NOT NULL AND revoked_at IS NOT NULL) AND ((revoked_at IS NULL AND revoke_reason IS NULL) OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))),
 CONSTRAINT ck_iam_credential_enrollment_time CHECK(isfinite(issued_at) AND isfinite(expires_at) AND expires_at>issued_at AND (consumed_at IS NULL OR isfinite(consumed_at)) AND (revoked_at IS NULL OR isfinite(revoked_at))),
 CONSTRAINT ck_iam_credential_enrollment_reason CHECK((char_length(issue_reason) BETWEEN 1 AND 500 AND issue_reason=btrim(issue_reason,' ') AND issue_reason !~ U&'[\0001-\001F\007F-\009F]' AND issue_reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$') AND (revoke_reason IS NULL OR (char_length(revoke_reason) BETWEEN 1 AND 500 AND revoke_reason=btrim(revoke_reason,' ') AND revoke_reason !~ U&'[\0001-\001F\007F-\009F]' AND revoke_reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$'))),
 CONSTRAINT ck_iam_credential_enrollment_approval CHECK(char_length(issuer_subject)>0 AND issuer_subject=btrim(issuer_subject,' ') AND issuer_subject !~ U&'[\0001-\001F\007F-\009F]' AND char_length(approval_ref)>0 AND approval_ref=btrim(approval_ref,' ') AND approval_ref !~ U&'[\0001-\001F\007F-\009F]'),
 CONSTRAINT ck_iam_credential_enrollment_version CHECK(version>=0)
);
CREATE UNIQUE INDEX uq_iam_credential_enrollment_pending_user ON iam_credential_enrollment(company_id,user_id) WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE TABLE iam_password_credential (
 user_id uuid NOT NULL, company_id uuid NOT NULL, encoded_password varchar(512) NOT NULL, input_policy varchar(16) NOT NULL,
 version bigint NOT NULL, created_at timestamptz NOT NULL, changed_at timestamptz NOT NULL, last_change_reason varchar(500) NOT NULL, last_enrollment_id uuid NOT NULL,
 CONSTRAINT pk_iam_password_credential PRIMARY KEY(user_id),
 CONSTRAINT fk_iam_password_credential_user FOREIGN KEY(company_id,user_id) REFERENCES iam_user(company_id,id) ON UPDATE NO ACTION ON DELETE RESTRICT NOT DEFERRABLE,
 CONSTRAINT fk_iam_password_credential_enrollment FOREIGN KEY(company_id,user_id,last_enrollment_id) REFERENCES iam_credential_enrollment(company_id,user_id,id) ON UPDATE NO ACTION ON DELETE RESTRICT NOT DEFERRABLE,
 CONSTRAINT ck_iam_password_credential_version CHECK(version>=0),
 CONSTRAINT ck_iam_password_credential_policy CHECK(input_policy='NFC_V1'),
 CONSTRAINT ck_iam_password_credential_hash CHECK(encoded_password LIKE '{kflow-argon2id-v1}$argon2id$v=19$m=%,t=%,p=%$%$%' AND char_length(encoded_password) BETWEEN 90 AND 512),
 CONSTRAINT ck_iam_password_credential_reason CHECK(char_length(last_change_reason) BETWEEN 1 AND 500 AND last_change_reason=btrim(last_change_reason,' ') AND last_change_reason !~ U&'[\0001-\001F\007F-\009F]' AND last_change_reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$'),
 CONSTRAINT ck_iam_password_credential_time CHECK(isfinite(created_at) AND isfinite(changed_at))
);
CREATE TABLE iam_bootstrap_record (
 company_id uuid NOT NULL, plan_id uuid NOT NULL, environment_id varchar(100) NOT NULL, plan_digest bytea NOT NULL,
 employee_id uuid NOT NULL, login_key varchar(64) NOT NULL, role_code varchar(32) NOT NULL, planned_grant_id uuid NOT NULL,
 user_id uuid, role_id uuid, phase varchar(16) NOT NULL, initiator_subject varchar(200) NOT NULL, approval_ref varchar(200) NOT NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, last_change_reason varchar(500) NOT NULL, version bigint NOT NULL,
 CONSTRAINT pk_iam_bootstrap_record_company PRIMARY KEY(company_id),
 CONSTRAINT uq_iam_bootstrap_record_plan UNIQUE(plan_id),
 CONSTRAINT fk_iam_bootstrap_record_company FOREIGN KEY(company_id) REFERENCES organization_company(id) ON UPDATE NO ACTION ON DELETE RESTRICT NOT DEFERRABLE,
 CONSTRAINT fk_iam_bootstrap_record_user FOREIGN KEY(company_id,user_id) REFERENCES iam_user(company_id,id) ON UPDATE NO ACTION ON DELETE RESTRICT NOT DEFERRABLE,
 CONSTRAINT fk_iam_bootstrap_record_role FOREIGN KEY(company_id,role_id) REFERENCES iam_role(company_id,id) ON UPDATE NO ACTION ON DELETE RESTRICT NOT DEFERRABLE,
 CONSTRAINT ck_iam_bootstrap_record_phase CHECK(phase IN ('CLAIMED','PREPARED','SEALED','ABORTED') AND (phase NOT IN ('PREPARED','SEALED') OR (user_id IS NOT NULL AND role_id IS NOT NULL))),
 CONSTRAINT ck_iam_bootstrap_record_digest CHECK(octet_length(plan_digest)=32),
 CONSTRAINT ck_iam_bootstrap_record_version CHECK(version>=0),
 CONSTRAINT ck_iam_bootstrap_record_reason CHECK(char_length(last_change_reason) BETWEEN 1 AND 500 AND last_change_reason=btrim(last_change_reason,' ') AND last_change_reason !~ U&'[\0001-\001F\007F-\009F]' AND last_change_reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$'),
 CONSTRAINT ck_iam_bootstrap_record_identity CHECK(login_key ~ '^[a-z0-9][a-z0-9._-]{2,63}$' AND role_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$' AND char_length(environment_id)>0 AND environment_id=btrim(environment_id,' ') AND environment_id !~ U&'[\0001-\001F\007F-\009F]' AND char_length(initiator_subject)>0 AND initiator_subject=btrim(initiator_subject,' ') AND initiator_subject !~ U&'[\0001-\001F\007F-\009F]' AND char_length(approval_ref)>0 AND approval_ref=btrim(approval_ref,' ') AND approval_ref !~ U&'[\0001-\001F\007F-\009F]'),
 CONSTRAINT ck_iam_bootstrap_record_time CHECK(isfinite(created_at) AND isfinite(updated_at))
);
