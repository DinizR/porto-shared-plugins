# auth-token

Shared processor for token issue and self-service password change.

| | |
|---|---|
| **Id** | `auth-token` |
| **Class** | `systems.porto.shinemedia.processor.AuthTokenProcessor` |
| **JAR** | `plugins/processors/shared/auth-token-1.0.0.jar` |
| **YAML** | `plugins/processors/{app}/auth-token-{env}.yaml` (optional) |
| **DTOs** | `systems.porto.api.auth.dto.*` in `porto-api-common` |

## Operations

| Operation | HTTP (via auth-token-rest) | Body |
|---|---|---|
| `auth-token-create` | `POST /auth/token` | `CreateAuthTokenRequest` |
| `auth-password-update` | `POST /auth/password` | `UpdateAuthPasswordRequest` |

Both return `AuthTokenResponse` (`access_token`, `token_type`, `expires_in`,
`subject`, `username`, `profiles`, `permissions`, `must_change_password`).

`grant_type` on create:

| Value | IdP capability |
|---|---|
| `password` (default) | LOCAL |
| `refresh_token` | LOCAL or EXTERNAL if advertised |
| `authorization_code` | EXTERNAL |

The processor only talks to `RequestContext.identityProvider()`. It does not
know JDBC, Keycloak, or the user table.

## Registry

```yaml
  - id: auth-token
    version: 1.0.0
    className: systems.porto.shinemedia.processor.AuthTokenProcessor
    dependencies:
      external:
        - jarFile: porto-api-common-1.0.0.jar
```

No `shine-media-dtos` dependency.
