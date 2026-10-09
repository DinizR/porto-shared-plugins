# email-smtp

Outbound SMTP for application notifications (not DocuSign “please sign” mail).

| | |
|---|---|
| **Id** | `email-smtp` |
| **Class** | `systems.porto.shinemedia.adapter.email.EmailSmtpAdapter` |
| **Capability** | `systems.porto.shinemedia.email.EmailSender` |
| **JAR** | `plugins/client-adapters/shared/email-smtp-1.0.0.jar` |
| **YAML** | `plugins/client-adapters/{app}/email-smtp-{env}.yaml` |

## Config keys

| Key | Default | Meaning |
|---|---|---|
| `smtp.enabled` | `true` | `false` logs and skips send |
| `smtp.host` | `localhost` | SMTP host |
| `smtp.port` | `1025` | Mailpit locally; `587` for Outlook / SES |
| `smtp.username` | empty | Empty = no AUTH |
| `smtp.password` | empty | Prefer `smtp.passwordFile` |
| `smtp.passwordFile` | empty | Path under `PORTO_API_HOME` or absolute |
| `smtp.from` | `noreply@shinemedia.local` | Envelope From |
| `smtp.redirectTo` | empty | Force every To to this inbox (dev) |
| `smtp.startTls` | `false` | Required for most public relays |

Password resolution: `smtp.password`, then `smtp.passwordFile`, then
`SHINE_SMTP_PASSWORD`.

## Behaviour

- Implements `EmailSender.send(OutboundEmail)` (to, cc, subject, text/html, attachments).
- Rebuilds Jakarta Mail `CommandMap` under the plugin classloader so PDF
  attachments resolve (host TCCL only has `jakarta.activation-api`).
- `smtp.redirectTo` sets `X-Original-To` / `X-Original-Cc` and drops real CC.

## App wiring

Processors bind this adapter as connector `EMAIL`. From/host/redirect stay in
the **application** YAML.
