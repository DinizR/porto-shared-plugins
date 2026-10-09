# document-storage-s3

Object store: local filesystem or S3 (including LocalStack / MinIO).

| | |
|---|---|
| **Id** | `document-storage-s3` |
| **Class** | `systems.porto.shinemedia.adapter.storage.DocumentStorageS3Adapter` |
| **Capability** | `systems.porto.shinemedia.storage.DocumentStorage` |
| **JAR** | `plugins/client-adapters/shared/document-storage-s3-1.0.0.jar` |
| **YAML** | `plugins/client-adapters/{app}/document-storage-s3-{env}.yaml` |

## API

`put` / `head` / `open` / `delete` by `storageKey`. Keys must not contain `..`.

## Config keys

| Key | Default | Meaning |
|---|---|---|
| `storage.backend` | `filesystem` | `filesystem` or `s3` |
| `storage.filesystem.root` | `data/shine-media/documents` | Default root (relative to `PORTO_API_HOME`) |
| `storage.filesystem.images-root` | `data/shine-media/images` | Keys starting with `images/` |
| `storage.filesystem.templates-root` | `data/shine-media/templates` | Keys starting with `sow-templates/` |
| `storage.filesystem.artefacts-root` | `data/shine-media/media-artefacts` | Keys starting with `media-artefacts/` |
| `storage.publicBaseUrl` | empty | Prefix for `StoredDocument.url` |
| `s3.bucket` | required when `s3` | Bucket name |
| `s3.region` | `ap-southeast-2` | AWS region |
| `s3.endpoint` | empty | Path-style override (LocalStack) |
| `s3.accessKeyId` / `s3.secretAccessKey` | empty | Else default AWS credentials |

Prefix routing (`images/`, `sow-templates/`, `media-artefacts/`) is Shine Media
convention today. Other apps can ignore those prefixes (they fall through to
`storage.filesystem.root`) or set the four roots to the same directory.

## App wiring

Processors bind connector `STORAGE`. Roots and bucket stay in the application YAML.
