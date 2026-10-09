# auth-token-rest

Registers the shared auth HTTP routes. No business logic.

| | |
|---|---|
| **Id** | `auth-token-rest` |
| **Class** | `systems.porto.shinemedia.entry.rest.AuthTokenRestEntryAdapter` |
| **JAR** | `plugins/entry-adapters/shared/auth-token-rest-1.0.0.jar` |
| **YAML** | `plugins/entry-adapters/{app}/auth-token-rest-{env}.yaml` |

## Routes

Relative to the app API base (`/api/v1.0.0` for Shine Media):

| Method | Path | Processor | Operation |
|---|---|---|---|
| `POST` | `/auth/token` | `auth-token` | `auth-token-create` |
| `POST` | `/auth/password` | `auth-token` | `auth-password-update` |

`/auth/token` is public (no bearer). `/auth/password` requires a valid token
even when the password is expired.

Keep these paths when swapping `local-idp` for a remote IdP.
