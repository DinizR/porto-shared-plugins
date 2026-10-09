package systems.porto.shinemedia.adapter.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import systems.porto.api.client.ConfiguredClientAdapter;
import systems.porto.context.Context;
import systems.porto.shinemedia.storage.DocumentStorage;
import systems.porto.shinemedia.storage.PutDocumentRequest;
import systems.porto.shinemedia.storage.InsertionSlotMediaKeys;
import systems.porto.shinemedia.storage.TemplateDocumentKeys;
import systems.porto.shinemedia.storage.StoredDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

/**
 * Document storage: {@code filesystem} (local free) or {@code s3} (prod / LocalStack).
 */
public class DocumentStorageS3Adapter extends ConfiguredClientAdapter<Context> implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageS3Adapter.class);

    private String backend;
    private Path filesystemRoot;
    private Path filesystemImagesRoot;
    private Path filesystemTemplatesRoot;
    private Path filesystemArtefactsRoot;
    private String publicBaseUrl;
    private S3Client s3;
    private String bucket;

    @Override
    public String id() {
        return "document-storage-s3";
    }

    @Override
    public void init(final Context context) {
        super.init(context);
        this.backend = configOr("storage.backend", "filesystem").trim().toLowerCase(Locale.ROOT);
        this.publicBaseUrl = configOr("storage.publicBaseUrl", "").replaceAll("/$", "");
        if ("filesystem".equals(backend)) {
            String root = configOr("storage.filesystem.root", "data/shine-media/documents");
            this.filesystemRoot = Paths.get(context.getHomeDirectory(), root).normalize();
            String imagesRoot = configOr("storage.filesystem.images-root", "data/shine-media/images");
            this.filesystemImagesRoot = Paths.get(context.getHomeDirectory(), imagesRoot).normalize();
            String templatesRoot = configOr(
                "storage.filesystem.templates-root", "data/shine-media/templates");
            this.filesystemTemplatesRoot = Paths.get(context.getHomeDirectory(), templatesRoot).normalize();
            String artefactsRoot = configOr(
                "storage.filesystem.artefacts-root", "data/shine-media/media-artefacts");
            this.filesystemArtefactsRoot = Paths.get(context.getHomeDirectory(), artefactsRoot).normalize();
            try {
                Files.createDirectories(filesystemRoot);
                Files.createDirectories(filesystemImagesRoot);
                Files.createDirectories(filesystemTemplatesRoot);
                Files.createDirectories(filesystemArtefactsRoot);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create filesystem storage roots: "
                    + filesystemRoot + " / " + filesystemImagesRoot + " / "
                    + filesystemTemplatesRoot + " / " + filesystemArtefactsRoot, e);
            }
            log.info("document-storage-s3 backend=filesystem documents={} images={} templates={} artefacts={}",
                filesystemRoot, filesystemImagesRoot, filesystemTemplatesRoot, filesystemArtefactsRoot);
            return;
        }
        if (!"s3".equals(backend)) {
            throw new IllegalStateException("storage.backend must be filesystem or s3; got " + backend);
        }
        this.bucket = requiredConfig("s3.bucket");
        String region = configOr("s3.region", "ap-southeast-2");
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        String endpoint = configOr("s3.endpoint", "");
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            builder.forcePathStyle(true);
        }
        String accessKey = configOr("s3.accessKeyId", "");
        String secretKey = configOr("s3.secretAccessKey", "");
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        this.s3 = builder.build();
        log.info("document-storage-s3 backend=s3 bucket={} region={}", bucket, region);
    }

    @Override
    public void finish() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Override
    public StoredDocument put(final PutDocumentRequest request) {
        requireKey(request.storageKey());
        byte[] content = request.content() != null ? request.content() : new byte[0];
        String contentType = request.contentType() != null && !request.contentType().isBlank()
            ? request.contentType()
            : "application/octet-stream";
        if ("filesystem".equals(backend)) {
            Path target = resolveFs(request.storageKey());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write document " + request.storageKey(), e);
            }
            return StoredDocument.builder()
                .storageKey(request.storageKey())
                .contentType(contentType)
                .sizeBytes(content.length)
                .url(publicUrl(request.storageKey()))
                .build();
        }
        PutObjectRequest put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(request.storageKey())
            .contentType(contentType)
            .build();
        s3.putObject(put, RequestBody.fromBytes(content));
        return StoredDocument.builder()
            .storageKey(request.storageKey())
            .contentType(contentType)
            .sizeBytes(content.length)
            .url(publicUrl(request.storageKey()))
            .build();
    }

    @Override
    public Optional<StoredDocument> head(final String storageKey) {
        requireKey(storageKey);
        if ("filesystem".equals(backend)) {
            Path target = resolveFs(storageKey);
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            try {
                return Optional.of(StoredDocument.builder()
                    .storageKey(storageKey)
                    .contentType(Files.probeContentType(target))
                    .sizeBytes(Files.size(target))
                    .url(publicUrl(storageKey))
                    .build());
            } catch (IOException e) {
                throw new RuntimeException("Failed to head document " + storageKey, e);
            }
        }
        try {
            var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return Optional.of(StoredDocument.builder()
                .storageKey(storageKey)
                .contentType(head.contentType())
                .sizeBytes(head.contentLength() != null ? head.contentLength() : 0L)
                .url(publicUrl(storageKey))
                .build());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public InputStream open(final String storageKey) {
        requireKey(storageKey);
        if ("filesystem".equals(backend)) {
            Path target = resolveFs(storageKey);
            try {
                return new ByteArrayInputStream(Files.readAllBytes(target));
            } catch (IOException e) {
                throw new RuntimeException("Failed to open document " + storageKey, e);
            }
        }
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
    }

    @Override
    public void delete(final String storageKey) {
        requireKey(storageKey);
        if ("filesystem".equals(backend)) {
            try {
                Files.deleteIfExists(resolveFs(storageKey));
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete document " + storageKey, e);
            }
            return;
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
    }

    private Path resolveFs(final String storageKey) {
        Path root = filesystemRoot;
        String relative = storageKey;
        if (storageKey.startsWith("images/")) {
            root = filesystemImagesRoot;
            relative = storageKey.substring("images/".length());
        } else if (storageKey.startsWith(TemplateDocumentKeys.STORAGE_PREFIX)) {
            // Shared latest HTML → data/shine-media/templates/{key}.html
            root = filesystemTemplatesRoot;
            relative = storageKey.substring(TemplateDocumentKeys.STORAGE_PREFIX.length());
        } else if (storageKey.startsWith(InsertionSlotMediaKeys.STORAGE_PREFIX)) {
            // Slot artefacts → data/shine-media/media-artefacts/{slotId}/{contentId}.{ext}
            root = filesystemArtefactsRoot;
            relative = storageKey.substring(InsertionSlotMediaKeys.STORAGE_PREFIX.length());
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("storageKey escapes filesystem root: " + storageKey);
        }
        return resolved;
    }

    private String publicUrl(final String storageKey) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return publicBaseUrl + "/" + storageKey;
        }
        if ("s3".equals(backend)) {
            return "s3://" + bucket + "/" + storageKey;
        }
        return "file://" + resolveFs(storageKey).toAbsolutePath();
    }

    private static void requireKey(final String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        if (storageKey.contains("..")) {
            throw new IllegalArgumentException("storageKey must not contain '..'");
        }
    }
}
