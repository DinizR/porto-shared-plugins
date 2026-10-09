# e-sign-docusign

Dual-party envelopes: local stub or DocuSign JWT + REST.

| | |
|---|---|
| **Id** | `e-sign-docusign` |
| **Class** | `systems.porto.shinemedia.adapter.esign.ESignDocusignAdapter` |
| **Capability** | `systems.porto.shinemedia.esign.ESign` |
| **JAR** | `plugins/client-adapters/shared/e-sign-docusign-1.0.0.jar` |
| **YAML** | `plugins/client-adapters/{app}/e-sign-docusign-{env}.yaml` |

DocuSign emails signers. Use [`email-smtp`](email-smtp.md) for app notifications.

## Config keys

| Key | Default | Meaning |
|---|---|---|
| `esign.backend` | — | `stub` or `docusign` |
| `esign.stub.root` | — | Directory for stub envelopes (outside the API tree) |
| `docusign.integrationKey` | — | Integration Key GUID |
| `docusign.userId` | — | User ID GUID |
| `docusign.accountId` | — | API Account ID GUID |
| `docusign.authServer` | — | e.g. `https://account-d.docusign.com` |
| `docusign.baseUri` | — | e.g. `https://demo.docusign.net/restapi` |
| `docusign.privateKeyPem` | empty | Inline PKCS#8 |
| `docusign.privateKeyPath` | empty | PEM file (relative to `PORTO_API_HOME`) |
| `docusign.webhookSecret` | empty | Connect HMAC; verifies `X-DocuSign-Signature-1` |

## API

`createEnvelope`, `getStatus`, `downloadSignedDocument`, `acceptWebhook`.

Sign-here tabs use AutoPlace anchors `/sig-provider/` and `/sig-shine/`
(Shine Media SoW HTML). Other apps must emit the same anchors or fork the
adapter.

## App wiring

Processors bind connector `ESIGN`. Keys and stub root stay in the application YAML.
