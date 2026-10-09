# local-idp

`IdentityProvider` for [LOCAL / platform JWT](../identity-providers.md).

| | |
|---|---|
| **Id** | `local-idp` |
| **Class** | `systems.porto.shinemedia.adapter.idp.LocalIdentityProviderAdapter` |
| **Kind** | `IdentityProviderKind.LOCAL` |
| **JAR** | `plugins/client-adapters/shared/local-idp-1.0.0.jar` |
| **YAML** | `plugins/client-adapters/{app}/local-idp-{env}.yaml` |

## What it does

1. Password grant: load account, verify secret, issue host JWT.
2. Each API call: host validates the JWT signature, then this plugin reloads
   the account, profiles, and permissions from the store.
3. `changePassword`: verify current secret, store a new host bcrypt hash, clear
   expiry, then issue a new token (via `auth-token`).

It never opens JDBC. Store and authorization adapters are selected by config.

## Config keys

| Key | Default | Meaning |
|---|---|---|
| `store.adapter` | `identity-store-jdbc` | Plugin id that implements `IdentityStore` |
| `authorization.adapter` | `identity-store-jdbc` | Plugin id that implements `AuthorizationSource` |
| `token.ttl` | host default (`PT8H`) | ISO-8601 duration for issued JWTs |

Host settings (not in this YAML): `porto.api.auth.identity-provider: local-idp`,
`porto.api.auth.jwt.issuer`, `porto.api.auth.jwt.secret` (≥ 32 bytes).

## Pairing

Typical: both keys point at [`identity-store-jdbc`](identity-store-jdbc.md).
You can split them (credentials in one adapter, roles in another) as long as
both plugin ids are loaded.

## Errors

| Case | Result |
|---|---|
| Bad user / password | `401` |
| Disabled account | `401` / `account_disabled` on later calls |
| Expired password | token still issued with `must_change_password: true`; other APIs `403` until `POST /auth/password` |
| New password &lt; 8 chars or same as current | `400` |
