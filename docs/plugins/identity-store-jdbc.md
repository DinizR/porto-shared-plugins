# identity-store-jdbc

JDBC implementation of `IdentityStore` and `AuthorizationSource` for
[`local-idp`](local-idp.md).

| | |
|---|---|
| **Id** | `identity-store-jdbc` |
| **Class** | `systems.porto.shinemedia.adapter.jdbc.IdentityStoreJdbcAdapter` |
| **JAR** | `plugins/client-adapters/shared/identity-store-jdbc-1.0.0.jar` |
| **YAML** | `plugins/client-adapters/{app}/identity-store-jdbc-{env}.yaml` |

This class contains **no SQL**. Each application maps its own tables by
implementing six named statements. That is the extension point: reuse the JAR,
rewrite the YAML.

## Config keys

| Key | Required | Meaning |
|---|---|---|
| `datasource` | yes | Named pool from the app datasource plugin (`shine-media`, …) |
| `sql.dialect` | yes | `h2`, `postgres`, or `mysql` (must match a `queries` block) |

## Required statements

Every dialect block must define all six names.

| Name | Parameters | Required result columns |
|---|---|---|
| `FIND_IDENTITY_BY_USERNAME` | `?` = username | `id`, `username`, `email`, `status`, `password_expires_at` |
| `FIND_IDENTITY_BY_SUBJECT` | `?` = subject (string form of `id`) | same as above |
| `FIND_IDENTITY_SECRET_HASH` | `?` = username | `password_hash` |
| `REPLACE_IDENTITY_SECRET` | `?1` = new bcrypt hash, `?2` = username | (update count) |
| `LIST_IDENTITY_PROFILES` | `?` = subject | first column = profile / role name |
| `LIST_IDENTITY_PERMISSIONS` | `?` = subject | first column = permission code |

Column **aliases** are the contract, not physical names. Map any table:

```sql
SELECT account_id AS id,
       login AS username,
       mail AS email,
       CASE WHEN active THEN 'ENABLED' ELSE 'DISABLED' END AS status,
       pwd_until AS password_expires_at
  FROM acct
 WHERE login = ?
```

## Semantics the Java adapter applies

- `subject` is `String.valueOf(id)`.
- Account is enabled when `status` is null, `ACTIVE`, or `ENABLED` (any case).
  Any other status disables login.
- `password_expires_at` null means the password is not expired.
- `REPLACE_IDENTITY_SECRET` must clear expiry (`password_expires_at = NULL` or
  equivalent). The hash is already bcrypt from the host `SecretHasher`.
- Profiles become JWT/Spring `ROLE_*` labels. Permissions are evaluated by
  `Identity.hasPermission` (`*`, `entity:*`, exact).

## What the application still owns

- Liquibase tables and seed admin
- Domain user columns (`first_name`, `party_id`, …)
- User / role CRUD plugins

If the model has no local passwords or no role tables, do not force this
adapter: implement `IdentityStore` (and optionally `AuthorizationSource`) in the
application.

Example YAML: [../examples/identity-store-jdbc.yaml](../examples/identity-store-jdbc.yaml).
Shine Media’s live mapping is
`plugins/client-adapters/shine-media/identity-store-jdbc-dev.yaml`.
