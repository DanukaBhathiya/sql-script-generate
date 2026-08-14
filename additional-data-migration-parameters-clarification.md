# Additional Data Migration Parameters Clarification

This document clarifies additional migration parameters requested for the CBM One App migration.

| # | Parameter | What This Field Means | Why the One App Requires It | Business Unit Confirmation - Is It Required or Not? |
|---|---|---|---|---|
| 1 | Migrated username | Customer's existing app username, carried over to the One App. After migration completion, the user can change it as per the One App requirement. | Allows customers to log in to the new CBM One App using the same username they already know, avoiding confusion on the first-time login. | Required. This value must be imported from `users.csv` and inserted into both `pending_user.username` and `pending_user.migrated_username`. Users may thereafter change usernames per One App rules. |
| 2 | FDAAccount Created on | The date on which the customer's account was originally created. | The new app retains an accurate record of when each customer's banking profile was first created. | Required. This value must be imported from `users.csv` and inserted into `pending_user.fda_account_created_date_time`. |
| 3 | FDA Account Status | The current status of the customer's account in the existing app, for example active, inactive, or locked. | Required so each customer's profile carries over with the correct status. Active customers stay active, and restricted profiles remain restricted. In addition, the full list of possible status values is required from the bank to complete the status mapping. | Required. This value must be imported from `users.csv` and inserted into `pending_user.fda_account_status`. CBM will provide the complete list of status values in the existing app, together with the agreed mapping to One App statuses, by 15/07/2026. |
| 4 | Remarks / Lock Reason | Reason recorded when a customer's account or profile has been locked or flagged by the bank. | Ensures locked profiles remain locked after migration, and support staff can see why, protecting the Bank's existing risk controls. | Required. This value must be imported from `users.csv` and inserted into `pending_user.fda_account_remarks`. It must remain visible to authorised back-office users only. |
| 5 | Number of OTP Attempts | How many OTP verification attempts the customer currently has recorded. | Carries over the customer's security state so OTP lockout protection is not reset by the migration. | Required. This value must be imported from users.csv and inserted into `pending_user.number_of_otp_attempts`. |
| 6 | Number of Login Attempts | How many failed login attempts the customer currently has recorded. | Carries over the customer's security state so login lockout protection is not reset by the migration. | Required. This value must be imported from users.csv and inserted into `pending_user.number_of_login_attempts`. |

## Summary

Required to migrate:

- Migrated username
- FDAAccount Created on
- FDA Account Status
- Remarks / Lock Reason
- Number of OTP Attempts
- Number of Login Attempts

## Open Confirmation

The full list of existing FDA account status values and the agreed mapping to One App statuses must be provided by the bank by `15/07/2026`.
