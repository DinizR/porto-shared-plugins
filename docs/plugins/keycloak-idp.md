# keycloak-idp

`IdentityProvider` for [EXTERNAL / remote IdP](../identity-providers.md).

| | |
|---|---|
| **Id** | `keycloak-idp` |
| **Class** | `systems.porto.api.adapter.idp.KeycloakIdentityProviderAdapter` |
| **Kind** | `IdentityProviderKind.EXTERNAL` |
| **JAR** | `plugins/client-adapters/shared/keycloak-idp-1.0.0.jar` |
| **YAML** | `plugins/client-adapters/{app}/keycloak-idp-{env}.yaml` |

## What it does

1. Password, authorization-code, or refresh grant: exchange with Keycloak’s
   token endpoint (`token-uri`). The host does **not** sign the JWT.
2. Each API call: the host validates the JWT against Keycloak JWKS
   (`jwks-uri`, `AccessTokenService.validateExternal`). This plugin then
   reloads profiles and permissions from the application store by
   `preferred_username`.
3. `changePassword` is not implemented (`403`). Change passwords in Keycloak.

It never opens JDBC. Store and authorization adapters are selected by config.

`issuer`, `jwks-uri`, and `token-uri` live in **this plugin’s YAML** in the
runtime home (`plugins/client-adapters/{app}/keycloak-idp-{env}.yaml`). Do not
put them on the host `application.yml`.

## JWKS (public keys)

The API process must be able to HTTP GET `jwks-uri` (Keycloak, or a proxy that
serves the same JWKS). The host caches the key set in memory (`RemoteJWKSet`):
first bearer check fetches it; later checks reuse the cache (Nimbus default:
refresh after 5 minutes, expire after 15). There is no file-based public key
path. If Keycloak is unreachable before the first fetch, or after the cache
expires, validation fails.

Set `jwks-uri` when the API cannot use `{issuer}/protocol/openid-connect/certs`
as-is (internal hostname, ingress, sidecar). `issuer` on the token must still
match the `issuer` key exactly.

## Config keys

| Key | Default | Meaning |
|---|---|---|
| `issuer` | required | Realm issuer, e.g. `http://localhost:8080/realms/shine-media` |
| `jwks-uri` | `{issuer}/protocol/openid-connect/certs` | Public-key (JWKS) URL the host fetches and caches |
| `token-uri` | `{issuer}/protocol/openid-connect/token` | Token endpoint for password / code / refresh |
| `client-id` | required | Keycloak client |
| `client-secret` | empty | Required for a confidential client |
| `audience` | empty | If set, `aud` or `azp` must match |
| `store.adapter` | `identity-store-jdbc` | Maps username → local subject |
| `authorization.adapter` | `identity-store-jdbc` | Loads profiles / permissions |
| `password.grant` | `true` | Direct access grants for API tests |

Host setting: `porto.api.auth.identity-provider: keycloak-idp`.

## Pairing

Keep [`identity-store-jdbc`](identity-store-jdbc.md) loaded so `ADMIN` /
`PROVIDER` permissions stay in the application database. Credentials live in
Keycloak; `users.password_hash` is unused.

## Local realm

Shine Media’s test realm and users: `shine-media-api-deploy/keycloak/`.
