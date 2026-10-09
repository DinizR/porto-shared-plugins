# document-render-thymeleaf

Renders **inline** Thymeleaf HTML to processed HTML or PDF. Template bytes come
from [`document-storage-s3`](document-storage-s3.md); this plugin does not read files.

| | |
|---|---|
| **Id** | `document-render-thymeleaf` |
| **Class** | `systems.porto.shinemedia.adapter.document.DocumentRenderThymeleafAdapter` |
| **Capability** | `systems.porto.shinemedia.document.DocumentRenderer` |
| **JAR** | `plugins/client-adapters/shared/document-render-thymeleaf-1.0.0.jar` |
| **YAML** | `plugins/client-adapters/{app}/document-render-thymeleaf-{env}.yaml` (may be empty) |

## API

- `renderPdfFromHtml(html, model)`
- `renderHtmlFromHtml(html, model)`

If `model` is a Shine Media `ProviderSowDocumentModel` or
`InsertionSowDocumentModel`, known variables (`company`, `sow`, `lines`, …) are
bound. Any other model is exposed as Thymeleaf variable `model`.

## Config

No required keys. Capability is enough for processors (`RENDER`).

## App wiring

SoW / proposal processors pass HTML from storage plus their document model.
Other apps can pass any object and use `${model.…}` in the template.
