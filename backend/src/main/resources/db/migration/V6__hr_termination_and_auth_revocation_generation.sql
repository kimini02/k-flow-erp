-- HR-04 / AN-03 AM-02 HR writer only. No IAM or session schema.
-- A pre-existing TERMINATED row without a real reason intentionally fails this migration.
ALTER TABLE hr_employee
    ADD COLUMN termination_reason varchar(500),
    ADD COLUMN auth_revocation_generation bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_hr_employee_auth_revocation_generation CHECK (auth_revocation_generation >= 0),
    ADD CONSTRAINT ck_hr_employee_termination_reason CHECK (
        (employment_status IN ('EMPLOYED','ON_LEAVE') AND termination_reason IS NULL)
        OR (employment_status='TERMINATED' AND termination_reason IS NOT NULL
            AND char_length(termination_reason) BETWEEN 1 AND 500
            AND termination_reason=btrim(termination_reason,' ')
            AND termination_reason !~ U&'[\0001-\001F\007F-\009F]'
            AND termination_reason !~ U&'^[ \00A0\1680\2000-\200A\2028\2029\202F\205F\3000]*$')
    );
