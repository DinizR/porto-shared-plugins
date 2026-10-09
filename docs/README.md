# porto-shared-plugins

Infrastructural plugins any porto-api application can load. JARs copy to
`plugins/{layer}/shared/`. Per-app YAML (hosts, buckets, SQL) stays under
`plugins/{layer}/{application}/`.

These plugins must not own an application schema. Local auth maps tables through
YAML SQL. A remote IdP does not use a local password table.

## Catalog

| Plugin | Layer | Role |
|---|---|---|
| [local-idp](plugins/local-idp.md) | client-adapter | Local accounts + host-signed JWT |
| [keycloak-idp](plugins/keycloak-idp.md) | client-adapter | Keycloak-issued JWT + app-store permissions |
| [identity-store-jdbc](plugins/identity-store-jdbc.md) | client-adapter | JDBC store for local IdP (SQL in YAML) |
| [auth-token](plugins/auth-token.md) | processor | `POST /auth/token` and `POST /auth/password` use cases |
| [auth-token-rest](plugins/auth-token-rest.md) | entry-adapter | HTTP routes for those use cases |
| [email-smtp](plugins/email-smtp.md) | client-adapter | Outbound SMTP |
| [document-storage-s3](plugins/document-storage-s3.md) | client-adapter | Filesystem or S3 object store |
| [document-render-thymeleaf](plugins/document-render-thymeleaf.md) | client-adapter | Inline HTML → HTML / PDF |
| [e-sign-docusign](plugins/e-sign-docusign.md) | client-adapter | Stub or DocuSign envelopes |

Identity model (local vs remote): [identity-providers.md](identity-providers.md).

## Load an application

1. Copy the shared JARs (`mvn package` with `porto.api.home` set).
2. Register each plugin id in the app’s `client-adapters-{env}.yaml` /
   `processors-{env}.yaml` / `entry-adapters-{env}.yaml`.
3. Keep app YAML next to the app (`plugins/{layer}/{app}/{id}-{env}.yaml`).
4. For local IdP, write identity SQL as documented in
   [identity-store-jdbc.md](plugins/identity-store-jdbc.md).
5. Set `porto.api.auth.identity-provider` to `local-idp` or `keycloak-idp`.

## What never lives here

- Application Liquibase changelogs and seed users
- Domain user CRUD (`user-jdbc`, party links, photos)
- Application datasources (`shine-media`, `registry`, …)
