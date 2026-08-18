# Operational Runbook: PayMedia OneApp User Migration & Status Update

## 1. Document Overview

This runbook provides step-by-step operational instructions for preparing, placing, validating, executing, monitoring, and rolling back the OneApp user migration process.

The process is designed for safe, repeatable, and traceable migration of user, beneficiary, and template data into the OneApp migration tables.

Vendor / Implementation Team: PayMedia

Project: OneApp User Migration

Service: User Migration Service - Spring Boot BFF

Endpoint: `/api/sql/generate`

Service Port: `8001`

## 2. Scope

This runbook covers the migration of:

- `users.csv`
- `beneficiaries.csv`
- `templates.csv`

The service also supports automatic batch-wise processing when the server CSV folder contains files such as:

- `users_1.csv`, `beneficiaries_1.csv`, `templates_1.csv`
- `users_2.csv`, `beneficiaries_2.csv`, `templates_2.csv`

Target migration tables:

- `pending_user`
- `migrate_beneficiary`
- `migrate_template`

## 3. Environment Details

| Environment | Component | Address / Port | Notes |
| --- | --- | --- | --- |
| UAT | UAT BFF Server | `172.16.147.46:8001` | Hosts User Migration Service |
| UAT | UAT BFF Server | `172.16.147.47:8001` | Hosts User Migration Service |
| UAT | UAT Database | `173.16.143.41` | PostgreSQL database network |
| Production | Prod BFF Server | `172.16.102.132:8001` | Hosts User Migration Service |
| Production | Production Database | `172.16.102.28` | PostgreSQL |

## 4. Roles and Responsibilities

| Team | Responsibility |
| --- | --- |
| Bank Team | Prepare source CSV files and copy files to the server CSV folder. |
| DB / Server Team | Confirm file placement, execute the endpoint, monitor logs, verify database counts, and perform rollback if required. |
| PayMedia Application Team | Support application/service issues, field mapping issues, log analysis, and migration troubleshooting. |
| Business / Application Owner | Approve production execution, confirm migrated data, and approve rollback when production data is involved. |

## 5. Migration Flow Summary

1. Bank Team prepares migration CSV files.
2. Bank Team copies files to the server CSV folder.
3. DB / Server Team confirms file placement.
4. DB / Server Team calls `/api/sql/generate`.
5. Service reads the CSV files and validates mandatory data.
6. Service generates SQL and migration evidence files.
7. If `executeToDb=false`, the service only generates SQL/output files.
8. If `executeToDb=true`, the service executes the generated SQL against PostgreSQL.
9. Service writes logs and successful migration CSV files.
10. DB / Server Team validates row counts and reports migration status.

## 6. Input File Naming

### Single Batch

Use these file names when only one migration batch is placed in the CSV folder:

| Source File | Required | Description |
| --- | --- | --- |
| `users.csv` | Yes | User migration records |
| `beneficiaries.csv` | Yes | Beneficiary migration records |
| `templates.csv` | Optional | Template migration records |

### Batch-wise Migration

Use matching suffixes when migration data is received batch wise:

| Batch | Users File | Beneficiaries File | Templates File |
| --- | --- | --- | --- |
| Batch 1 | `users_1.csv` | `beneficiaries_1.csv` | `templates_1.csv` |
| Batch 2 | `users_2.csv` | `beneficiaries_2.csv` | `templates_2.csv` |
| Batch N | `users_N.csv` | `beneficiaries_N.csv` | `templates_N.csv` |

When batch-wise files are present, the endpoint automatically discovers `users_N.csv` files and processes the matching `beneficiaries_N.csv` and `templates_N.csv` files for each batch.

If no `users_N.csv` files are found, the service falls back to the single-batch file names.

## 7. Pre-Migration Checklist

Owner: Bank Team and DB / Server Team

Timing: Before calling the migration endpoint

| Check | Task | Action Required |
| --- | --- | --- |
| [ ] | Environment confirmation | Confirm whether the migration is for UAT or Production. |
| [ ] | Server access | Confirm read/write access to the configured CSV, output, and log folders. |
| [ ] | File placement | Confirm CSV files are copied to the configured server CSV folder. |
| [ ] | File naming | Confirm either single-batch names or matching batch-wise names are used. |
| [ ] | Batch consistency | For each `users_N.csv`, confirm the matching `beneficiaries_N.csv` exists. |
| [ ] | Optional template file | Confirm whether `templates.csv` or `templates_N.csv` is expected for the batch. |
| [ ] | Header validation | Confirm headers match the agreed field mapping. |
| [ ] | Row count capture | Record source row count for each file before execution. |
| [ ] | Duplicate check | Confirm duplicate users, beneficiaries, and templates are understood before execution. |
| [ ] | Approval | Obtain execution approval from the Business / Application Owner for production. |

## 8. Mandatory Input Validation

The service performs row-level validation before SQL generation.

| Source File | Mandatory Fields Validated by Service | Failed Row Behavior |
| --- | --- | --- |
| `users.csv` / `users_N.csv` | `BANK_EMAIL`, `DIGESTED_PASSWORD`, `MOBILE`, `REGISTERED_ACCOUNT_NUMBER` | Row is skipped and written to fail summary and failed-users CSV. |
| `beneficiaries.csv` / `beneficiaries_N.csv` | `CIF`, `ACCOUNT_NUMBER`, `NICKNAME`, `TYPE` | Row is skipped and written to fail summary. |
| `templates.csv` / `templates_N.csv` | `CIF`, `TEMPLATE_NAME`, `RECIPIENT_BANK` | Row is skipped and written to fail summary. |

For beneficiaries, `TYPE=INTERNATIONAL` is converted to `INTERNATIONAL_TRANSFER` before SQL generation.

## 9. Additional Pending User Fields

The following additional parameters must be imported from `users.csv` and inserted into `pending_user`.

| Source Field / Alias | Target Field |
| --- | --- |
| `users.csv.USERNAME` | `pending_user.username` |
| `users.csv.USERNAME` | `pending_user.migrated_username` |
| `FDAACCOUNT_CREATED_ON` aliases | `pending_user.fda_account_created_date_time` |
| `FDAACCOUNT_STATUS` aliases | `pending_user.fda_account_status` |
| `REMARKS / LOCK REASON` aliases | `pending_user.fda_account_remarks` |
| `Number of OTP Attempts` aliases | `pending_user.number_of_otp_attempts` |
| `Number of Login Attempts` aliases | `pending_user.number_of_login_attempts` |

## 10. Endpoint Execution

Base endpoint:

```text
POST http://<BFF_SERVER>:8001/api/sql/generate
```

Recommended dry-run execution:

```text
POST http://<BFF_SERVER>:8001/api/sql/generate?saveToDisk=true&executeToDb=false
```

Recommended database execution:

```text
POST http://<BFF_SERVER>:8001/api/sql/generate?saveToDisk=true&executeToDb=true
```

Optional parameter:

| Parameter | Default | Purpose |
| --- | --- | --- |
| `saveToDisk` | `true` | Writes generated SQL, fail summary, and migration data CSV to the output folder. |
| `outputDir` | `generated` | Output folder for generated files. |
| `executeToDb` | `false` | Executes generated SQL against the configured database when set to `true`. |

## 11. Execution and Monitoring Checklist

Owner: DB / Server Team

Timing: During endpoint execution

| Check | Task | Action Required |
| --- | --- | --- |
| [ ] | Trigger dry run | Call the endpoint with `executeToDb=false`. |
| [ ] | Review generated SQL | Confirm generated SQL file is created in the output folder. |
| [ ] | Review fail summary | Confirm skipped rows are expected or corrected before DB execution. |
| [ ] | Confirm batch headers | Check response headers `X-Batch-Count` and `X-Migration-Batches`. |
| [ ] | Trigger DB execution | Call the endpoint with `executeToDb=true` only after approval. |
| [ ] | Monitor application logs | Confirm file read, SQL generation, and DB execution logs are written. |
| [ ] | Monitor DB execution | Confirm inserted count and skipped/conflict count match expectations. |
| [ ] | Capture evidence | Save request time, response headers, output file names, and log file names. |

## 12. Expected Logs

The application logs should show the migration lifecycle file by file.

| Phase | Expected Log Evidence |
| --- | --- |
| Source file read started | Batch ID, source file name, path, required flag, file size. |
| Source file read completed | Batch ID, source file, rows read, generated insert count, skipped count. |
| SQL generation started | SQL generation start marker. |
| SQL generation completed | Total insert count and skipped count. |
| DB execution started | Expected insert count. |
| Source file execution planned | Source file, source rows, expected insert count. |
| Insert line processed | Insert index, source file, source row, target table, status, update count. |
| Source file execution completed | Source rows, expected count, attempted count, inserted count, conflict skipped count, failed count. |
| Successful migration CSV created | File path and inserted row count for users, beneficiaries, and templates. |
| DB execution completed | Expected insert count, inserted count, conflict skipped count, duration. |

If a row fails validation, it is not converted to SQL and appears in the fail summary.

If a database insert conflicts with an existing beneficiary or template, `ON CONFLICT DO NOTHING` can result in a conflict-skipped row instead of a failure.

## 13. Output Files

When `saveToDisk=true`, the service writes migration output files.

| File Type | Example |
| --- | --- |
| Generated SQL | `migration_inserts_<timestamp>.sql` |
| Fail summary | `migration_inserts_<timestamp>_fail_summary.log` |
| Migration data CSV | `migration_inserts_<timestamp>_data.csv` |
| Users success CSV | `migration_inserts_<timestamp>_users_success.csv` |
| Users failed CSV | `migration_inserts_<timestamp>_users_failed.csv` |
| Batch generated SQL | `migration_inserts_batch-1_<timestamp>.sql` |
| Batch fail summary | `migration_inserts_batch-1_<timestamp>_fail_summary.log` |
| Batch migration data CSV | `migration_inserts_batch-1_<timestamp>_data.csv` |
| Batch users success CSV | `migration_inserts_batch-1_<timestamp>_users_success.csv` |
| Batch users failed CSV | `migration_inserts_batch-1_<timestamp>_users_failed.csv` |

When `executeToDb=true`, the service also writes successful migration CSV files.

| File Type | Example |
| --- | --- |
| Successful users | `migration_success_<timestamp>_users.csv` |
| Successful beneficiaries | `migration_success_<timestamp>_beneficiaries.csv` |
| Successful templates | `migration_success_<timestamp>_templates.csv` |
| Batch successful users | `migration_success_batch-1_<timestamp>_users.csv` |
| Batch successful beneficiaries | `migration_success_batch-1_<timestamp>_beneficiaries.csv` |
| Batch successful templates | `migration_success_batch-1_<timestamp>_templates.csv` |

Successful migration CSV files are the recommended evidence source for manual rollback because they contain only rows that were successfully inserted.

## 14. Post-Migration Verification Checklist

Owner: DB / Server Team and Business / Application Owner

Timing: Immediately after migration execution

| Check | Task | Action Required |
| --- | --- | --- |
| [ ] | Response status | Confirm endpoint returned success. |
| [ ] | DB execution header | Confirm `X-Db-Execution=EXECUTED` for DB execution runs. |
| [ ] | Batch count | Confirm `X-Batch-Count` matches expected batch count. |
| [ ] | Failure count | Confirm `X-Fail-Count` is zero or approved. |
| [ ] | Generated files | Confirm SQL, fail summary, and migration data files are present. |
| [ ] | Success files | Confirm successful users, beneficiaries, and templates files are present after DB execution. |
| [ ] | Row counts | Compare source rows, generated insert rows, inserted rows, skipped rows, and failed rows. |
| [ ] | Pending users | Verify expected records in `pending_user`. |
| [ ] | Beneficiaries | Verify expected records in `migrate_beneficiary`. |
| [ ] | Templates | Verify expected records in `migrate_template`. |
| [ ] | Additional fields | Verify migrated username, FDA account status/date/remarks, and attempt counts. |
| [ ] | Business sign-off | Confirm business owner accepts the migrated sample/counts. |

## 15. Status Update Format

Use this format when reporting migration status.

| Item | Status |
| --- | --- |
| Environment | UAT / Production |
| Execution date/time | `<date and time>` |
| Executed by | `<name/team>` |
| Endpoint | `/api/sql/generate` |
| Mode | Dry run / DB execution |
| Batch count | `<count>` |
| Files processed | `<file names>` |
| Users source rows | `<count>` |
| Beneficiaries source rows | `<count>` |
| Templates source rows | `<count>` |
| Generated insert count | `<count>` |
| Successfully inserted rows | `<count>` |
| Conflict skipped rows | `<count>` |
| Failed/skipped rows | `<count>` |
| Output files | `<paths>` |
| Success files | `<paths>` |
| Final status | Success / Partial success / Failed / Rolled back |

## 16. Commit and Rollback Behavior

When the endpoint is called with `executeToDb=true`, DB execution runs inside a Spring transaction.

If all insert statements in that execution complete successfully, the transaction is committed at the end of DB execution.

If any runtime database execution error occurs, the transaction is rolled back automatically for that execution.

For batch-wise migration, each discovered batch is generated and executed separately. This means each batch has its own DB execution transaction.

If `batch-1` commits successfully and `batch-2` fails later, `batch-1` is not automatically rolled back with `batch-2`. The failed batch is rolled back automatically, and any earlier committed batch must be rolled back manually if the business decides to reverse the full run.

## 17. Manual Rollback Approach

Manual rollback is required when data has already committed and the business decides to reverse the migration.

Recommended rollback evidence:

- Successful users CSV
- Successful beneficiaries CSV
- Successful templates CSV
- Batch-specific successful migration CSV files when batch-wise execution was used

Rollback order:

1. Delete templates from `migrate_template`.
2. Delete beneficiaries from `migrate_beneficiary`.
3. Delete users from `pending_user`.

Rollback control steps:

| Check | Task | Action Required |
| --- | --- | --- |
| [ ] | Stop migration | Confirm no new migration execution is running for the same data set. |
| [ ] | Identify batch | Capture batch ID, timestamp, request time, and output files. |
| [ ] | Take backup | Take database backup or snapshot before manual rollback. |
| [ ] | Load staging data | Load successful migration CSV files into rollback staging tables. |
| [ ] | Count before delete | Confirm matching row counts before delete. |
| [ ] | Execute rollback | Run delete statements inside a DB transaction. |
| [ ] | Verify after delete | Confirm matching rows are zero after delete. |
| [ ] | Commit or rollback | Commit only after DB / Server Team and owner confirmation. |
| [ ] | Audit evidence | Save rollback SQL, row counts, logs, and approvals. |

Refer to `user-migration-rollback-approach.docx` and `user-migration-rollback-solution.sql` for the detailed rollback solution.

## 18. Error Remediation Workflow

If the fail summary contains skipped rows or the endpoint fails, follow this workflow.

1. Identify the failed batch and file from logs or response headers.
2. Open the relevant fail summary file.
3. Review the source file, source row, query/table, status, and reason.
4. Correct the source CSV data.
5. Save corrected files using the same expected naming pattern or the next agreed batch suffix.
6. Re-run dry run with `executeToDb=false`.
7. Proceed to DB execution only after the fail summary and generated counts are approved.

Common remediation examples:

| Issue | Likely Cause | Action Required |
| --- | --- | --- |
| Missing mandatory user field | `BANK_EMAIL`, `DIGESTED_PASSWORD`, or `MOBILE` is missing. | Correct `users.csv` row and re-run. |
| Missing beneficiary key | `CIF`, `ACCOUNT_NUMBER`, `NICKNAME`, or `TYPE` is missing. | Correct `beneficiaries.csv` row and re-run. |
| Missing template key | `CIF`, `TEMPLATE_NAME`, or `RECIPIENT_BANK` is missing. | Correct `templates.csv` row and re-run. |
| Batch file not found | Matching `beneficiaries_N.csv` is missing for `users_N.csv`. | Place the missing file and re-run. |
| Duplicate beneficiary/template | Existing DB row conflicts with generated insert. | Confirm whether conflict skip is expected. |
| DB execution failed | Constraint, connection, or data issue during insert. | Review application log and database error; failed transaction rolls back automatically. |

## 19. Troubleshooting FAQ

### Q: Files are placed in the CSV folder, but the endpoint does not process batch files.

Confirm file names follow the pattern `users_N.csv`, `beneficiaries_N.csv`, and `templates_N.csv`. The service discovers batches from `users_N.csv`.

### Q: Only single-batch files were processed.

If no `users_N.csv` files exist, the service uses `users.csv`, `beneficiaries.csv`, and `templates.csv`.

### Q: Why does the response show conflicts but no failure?

Beneficiary and template inserts use `ON CONFLICT DO NOTHING`. Existing duplicate rows can be counted as conflict-skipped, not failed.

### Q: Does commit happen at the end of the full HTTP flow?

The DB commit happens at the end of each DB execution transaction. In batch-wise migration, each batch has its own execution transaction.

### Q: Can already committed data be automatically rolled back later?

No. Once a batch transaction commits, rollback after that point is manual and should use the successful migration CSV files.

### Q: Which file should be used for rollback?

Use the successful migration CSV files because they contain only rows that were actually inserted.

## 20. Final Go / No-Go Criteria

Proceed with production DB execution only when all criteria are met.

| Criteria | Required Status |
| --- | --- |
| CSV files placed in correct server folder | Complete |
| File names and batch grouping verified | Complete |
| Dry run completed | Complete |
| Fail summary reviewed | Approved |
| Generated insert counts reviewed | Approved |
| Database backup/snapshot readiness confirmed | Complete |
| Rollback approach reviewed | Complete |
| Business approval received | Complete |
| DB / Server Team execution window confirmed | Complete |
| PayMedia support availability confirmed | Complete |

## 21. Execution Evidence Checklist

Keep the following evidence for audit and support.

| Evidence | Required |
| --- | --- |
| Source CSV file names | Yes |
| Source row counts per file | Yes |
| Endpoint request timestamp | Yes |
| Endpoint response headers | Yes |
| Generated SQL files | Yes |
| Fail summary files | Yes |
| Migration data CSV files | Yes |
| Successful migration CSV files | Yes, for DB execution |
| Application logs | Yes |
| DB validation queries and counts | Yes |
| Business sign-off | Yes |
| Rollback approval and output, if rollback was performed | Conditional |
