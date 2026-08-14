-- User Migration Manual Rollback Solution
-- Use this only after a migration has already committed successfully.
-- Replace the staging table loading section with the approved method for the environment.

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Create temporary rollback staging tables.
--    Load these tables from the service-generated success CSV files:
--      migration_success_<timestamp>_users.csv
--      migration_success_<timestamp>_beneficiaries.csv
--      migration_success_<timestamp>_templates.csv
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE rollback_users (
    cif text,
    username text,
    mobile text,
    email text,
    identity_number text
) ON COMMIT DROP;

CREATE TEMP TABLE rollback_beneficiaries (
    cif text,
    account_number text,
    nickname text,
    type text
) ON COMMIT DROP;

CREATE TEMP TABLE rollback_templates (
    cif text,
    template_name text,
    to_account text,
    recipient_bank text,
    template_type text
) ON COMMIT DROP;

-- Example only. Use server-side COPY or client-side \copy as approved.
-- \copy rollback_users FROM 'migration_success_<timestamp>_users.csv' CSV HEADER
-- \copy rollback_beneficiaries FROM 'migration_success_<timestamp>_beneficiaries.csv' CSV HEADER
-- \copy rollback_templates FROM 'migration_success_<timestamp>_templates.csv' CSV HEADER

-- ---------------------------------------------------------------------------
-- 2. Verify rows that will be deleted.
-- ---------------------------------------------------------------------------

SELECT 'migrate_template' AS target_table, COUNT(*) AS matching_rows
FROM migrate_template mt
JOIN rollback_templates rt
  ON rt.cif = mt.cif
 AND rt.template_name = mt.template_name;

SELECT 'migrate_beneficiary' AS target_table, COUNT(*) AS matching_rows
FROM migrate_beneficiary mb
JOIN rollback_beneficiaries rb
  ON rb.cif = mb.cif
 AND rb.account_number = mb.account_number
 AND rb.nickname = mb.nickname
 AND rb.type = mb.type;

SELECT 'pending_user' AS target_table, COUNT(*) AS matching_rows
FROM pending_user pu
JOIN rollback_users ru
  ON ru.cif = pu.cif
 AND UPPER(ru.username) = UPPER(pu.username)
WHERE pu.migrate_user = true;

-- ---------------------------------------------------------------------------
-- 3. Delete in dependency-safe order.
-- ---------------------------------------------------------------------------

DELETE FROM migrate_template mt
USING rollback_templates rt
WHERE rt.cif = mt.cif
  AND rt.template_name = mt.template_name;

DELETE FROM migrate_beneficiary mb
USING rollback_beneficiaries rb
WHERE rb.cif = mb.cif
  AND rb.account_number = mb.account_number
  AND rb.nickname = mb.nickname
  AND rb.type = mb.type;

DELETE FROM pending_user pu
USING rollback_users ru
WHERE ru.cif = pu.cif
  AND UPPER(ru.username) = UPPER(pu.username)
  AND pu.migrate_user = true;

-- ---------------------------------------------------------------------------
-- 4. Verify rollback result before commit.
-- ---------------------------------------------------------------------------

SELECT 'migrate_template' AS target_table, COUNT(*) AS remaining_rows
FROM migrate_template mt
JOIN rollback_templates rt
  ON rt.cif = mt.cif
 AND rt.template_name = mt.template_name;

SELECT 'migrate_beneficiary' AS target_table, COUNT(*) AS remaining_rows
FROM migrate_beneficiary mb
JOIN rollback_beneficiaries rb
  ON rb.cif = mb.cif
 AND rb.account_number = mb.account_number
 AND rb.nickname = mb.nickname
 AND rb.type = mb.type;

SELECT 'pending_user' AS target_table, COUNT(*) AS remaining_rows
FROM pending_user pu
JOIN rollback_users ru
  ON ru.cif = pu.cif
 AND UPPER(ru.username) = UPPER(pu.username)
WHERE pu.migrate_user = true;

-- If verification is correct:
-- COMMIT;

-- If verification is not correct:
-- ROLLBACK;

