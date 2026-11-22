-- Item/Unit provider only. Unit is a fixed approved catalog, not an editable business master.
-- No stock balance, Partner/Warehouse, PO/GR, LOT/inspection flags, prices for transactions, or seed Items.
CREATE TABLE masterdata_item (
    id UUID NOT NULL,
    company_id UUID NOT NULL,
    code VARCHAR(32) COLLATE "C" NOT NULL,
    name VARCHAR(100) NOT NULL,
    specification VARCHAR(500),
    category VARCHAR(32) NOT NULL,
    usage_profile VARCHAR(32) NOT NULL,
    base_uom VARCHAR(8) NOT NULL,
    stock_managed BOOLEAN NOT NULL,
    reference_price NUMERIC,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    first_used_at TIMESTAMPTZ,
    first_use_kind VARCHAR(24),
    first_use_source_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_masterdata_item PRIMARY KEY (id),
    CONSTRAINT fk_masterdata_item_company FOREIGN KEY (company_id) REFERENCES organization_company(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT uq_masterdata_item_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_masterdata_item_id CHECK (id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_masterdata_item_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_masterdata_item_name CHECK (char_length(name) BETWEEN 1 AND 100
        AND name = btrim(name, ' ') AND name !~ '[[:cntrl:]]' AND btrim(name, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000') <> ''),
    CONSTRAINT ck_masterdata_item_specification CHECK (specification IS NULL OR
        (char_length(specification) BETWEEN 1 AND 500 AND specification = btrim(specification, ' ')
         AND specification !~ '[[:cntrl:]]' AND btrim(specification, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000') <> '')),
    CONSTRAINT ck_masterdata_item_profile CHECK (stock_managed AND (
        (category = 'FINISHED_GOOD' AND usage_profile = 'MANUFACTURED_FINISHED') OR
        (category = 'RAW_MATERIAL' AND usage_profile = 'PURCHASED_MATERIAL') OR
        (category = 'MERCHANDISE' AND usage_profile = 'RESALE'))),
    CONSTRAINT ck_masterdata_item_uom CHECK (base_uom IN ('EA', 'SET', 'BOX', 'KG')),
    CONSTRAINT ck_masterdata_item_reference_price CHECK (reference_price IS NULL OR
        (reference_price >= 0 AND reference_price <= 999999999999 AND reference_price = trunc(reference_price))),
    CONSTRAINT ck_masterdata_item_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_masterdata_item_version CHECK (version >= 0),
    CONSTRAINT ck_masterdata_item_first_use CHECK (
        (first_used_at IS NULL AND first_use_kind IS NULL AND first_use_source_id IS NULL) OR
        (first_used_at IS NOT NULL AND first_use_kind IS NOT NULL AND first_use_source_id IS NOT NULL
         AND isfinite(first_used_at) AND first_use_kind IN ('PR_APPROVAL', 'PO_CONFIRMATION', 'STOCK_POSTING')
         AND first_use_source_id <> '00000000-0000-0000-0000-000000000000'::uuid)),
    CONSTRAINT ck_masterdata_item_timestamps CHECK (isfinite(created_at) AND isfinite(updated_at))
);
-- Current company/code uniqueness is the list access path. No speculative consumer composite key.
