# Identity providers

The host never imports Spring Security or Keycloak types. It binds one
`IdentityProvider` plugin from `porto.api.auth.identity-provider` and uses that
for login, bearer validation, and optional password change.

```
POST /auth/token  ──► auth-token-rest ──► auth-token ──► IdentityProvider
Authorization     ──► BearerAuthenticationFilter     ──► IdentityProvider.validateAccessToken
```

## LOCAL — platform JWT

Plugin: [`local-idp`](plugins/local-idp.md).

- Accounts live in the **application database**.
- `local-idp` verifies the password through `IdentityStore` (usually
  [`identity-store-jdbc`](plugins/identity-store-jdbc.md)).
- The **host** (`AccessTokenService` / HMAC JWT) signs and validates tokens.
- Profiles and permissions are loaded from the store on every request, not from
  the JWT payload.
- Password expiry and `POST /auth/password` are local-only.

`IdentityProviderKind.LOCAL` and `IdentityProviderCapabilities.localJwt()`:
password grant, host-issued tokens, optional refresh. No authorization-code.

## EXTERNAL — remote IdP (Keycloak, …)

Plugin: [`keycloak-idp`](plugins/keycloak-idp.md).

- Accounts and credentials live on the IdP server.
- Tokens are issued by that server. The host validates them with the IdP
  public keys from plugin YAML `jwks-uri` (cached in memory after the first
  fetch). Login uses `token-uri`.
  (`IdentityProviderCapabilities.externalIdp()` / `externalIdpWithPassword()`).
- `POST /auth/token` stays the same route. Local/API tests can still use
  `password` when the Keycloak client has Direct access grants. Production
  clients should use `authorization_code` / `refresh_token`.
- `users.password_hash` is unused. The application user table remains a domain
  profile; roles and permissions are reloaded from `AuthorizationSource`.
- `changePassword` stays unimplemented (`403`).

Switch by adding the remote plugin and setting:

```yaml
porto:
  api:
    auth:
      identity-provider: keycloak-idp
```

Do not change `/auth/token` or `/auth/password` paths. Clients that only use
password grant stay on `local-idp` until you migrate them.

## What each side owns

| Concern | LOCAL | EXTERNAL |
|---|---|---|
| Password hash | App DB via store YAML | IdP |
| JWT issuer | Host (`porto.api.auth.jwt`) | IdP |
| Profiles / permissions | App DB (reloaded each call) | Token claims and/or app DB |
| User domain fields | App `users` (or equivalent) | App table or IdP attributes |
| Liquibase | App changelog | Not required for credentials |
