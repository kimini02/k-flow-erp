-- ORG-03: preserve existing rows and unknown historical reasons; no fabricated backfill.
ALTER TABLE organization_site
    ADD COLUMN state_changed_at timestamptz,
    ADD COLUMN last_state_change_reason varchar(500),
    ADD CONSTRAINT ck_organization_site_state_change CHECK (
        (state_changed_at IS NULL AND last_state_change_reason IS NULL)
        OR (state_changed_at IS NOT NULL AND last_state_change_reason IS NOT NULL
            AND char_length(last_state_change_reason) BETWEEN 1 AND 500
            AND last_state_change_reason = btrim(last_state_change_reason, ' ')
            AND last_state_change_reason <> '')
    );
ALTER TABLE organization_department
    ADD COLUMN state_changed_at timestamptz,
    ADD COLUMN last_state_change_reason varchar(500),
    ADD CONSTRAINT ck_organization_department_state_change CHECK (
        (state_changed_at IS NULL AND last_state_change_reason IS NULL)
        OR (state_changed_at IS NOT NULL AND last_state_change_reason IS NOT NULL
            AND char_length(last_state_change_reason) BETWEEN 1 AND 500
            AND last_state_change_reason = btrim(last_state_change_reason, ' ')
            AND last_state_change_reason <> '')
    );
ALTER TABLE organization_position
    ADD COLUMN state_changed_at timestamptz,
    ADD COLUMN last_state_change_reason varchar(500),
    ADD CONSTRAINT ck_organization_position_state_change CHECK (
        (state_changed_at IS NULL AND last_state_change_reason IS NULL)
        OR (state_changed_at IS NOT NULL AND last_state_change_reason IS NOT NULL
            AND char_length(last_state_change_reason) BETWEEN 1 AND 500
            AND last_state_change_reason = btrim(last_state_change_reason, ' ')
            AND last_state_change_reason <> '')
    );
