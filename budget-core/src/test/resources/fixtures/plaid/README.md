# Plaid fixtures

Every ingestion test runs against these files; none touches the network.

## Recorded (this directory)

Real Plaid **Sandbox** responses for `user_transactions_dynamic`, written by
`src/test/java/com/hasan/budget/ingestion/plaid/PlaidSandboxRecorder.java`. Re-record from the
repository root with:

```
java budget-core/src/test/java/com/hasan/budget/ingestion/plaid/PlaidSandboxRecorder.java
```

Access tokens are redacted. Every other byte is what Plaid sent.

| File | What it is |
|---|---|
| `sync-initial-page-1.json` | First full `/transactions/sync` after linking: 7 accounts, ~300 transactions |
| `sync-after-refresh-page-1.json` | The sync after `/transactions/refresh`: new rows, and every old row re-sent as `modified` |
| `sync-scenarios-page-1.json` | Rows prompted with `/sandbox/transactions/create` — rent, a savings transfer, the checking side of a card payment, a refund. We chose the description and amount; **Plaid chose the category**, exactly as for any other row |
| `transactions-recurring-get*.json` | `/transactions/recurring/get`, before and after the prompted rows |
| `item-*.json`, `link-token-create.json` | Link and exchange responses |

## What the sandbox actually sends, and the docs do not warn about

The mapping in `src/main/resources/com/hasan/budget/ingestion/plaid/pfc-mapping.csv` was built from
these files. Things that would have been mapped wrongly from the documentation alone:

- The taxonomy is **PFC v2**. Salary is `INCOME_SALARY`, not the v1 `INCOME_WAGES`.
- The credit card's `Payment Thank You-Mobile` (-2835.80) arrives as **`INCOME_SALARY`**. Mapped by
  category alone, paying a card bill would be booked as $2,835.80 of income.
- Loan accounts carry hundreds of `LOAN_DISBURSEMENTS_OTHER_DISBURSEMENT` inflows named "Loan
  payment", plus interest charges. None of it is money in or out of the user's pocket.
- Weekly payroll from a restaurant employer is tagged **`FOOD_AND_DRINK_RESTAURANT`**, and so is its
  recurring inflow stream.
- The ChatGPT subscription's stream is tagged `TRANSFER_OUT_ACCOUNT_TRANSFER`, while its own
  transactions say `GENERAL_SERVICES_OTHER_GENERAL_SERVICES`.
- Amounts can carry sub-cent precision (`48.2542`).

The first three are why the account's type takes part in classification, and the fourth and fifth
are why recurring streams re-classify their member transactions rather than being classified by
their own category.

## Constructed (`constructed/`)

Things the sandbox cannot produce. Each is built from real recorded rows or real response shapes,
and kept apart so nobody mistakes one for a recording.

| File | Why it had to be constructed |
|---|---|
| `transactions-recurring-get-with-rent.json` | The real scenario response plus a rent stream over the real rent transaction. The sandbox rejects custom transactions posted more than 14 days ago, so it can never accumulate the months of rent a stream needs |
| `sync-removes-one-purchase.json` | A later page retracting one real, settled purchase. The dynamic user's refresh modified rows but removed none |
| `webhook-*.json` | Payload shapes from Plaid's webhook documentation. The sandbox can only deliver webhooks to a public URL |
