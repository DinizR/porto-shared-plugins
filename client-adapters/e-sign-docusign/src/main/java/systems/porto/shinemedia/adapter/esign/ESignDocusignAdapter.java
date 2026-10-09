package systems.porto.shinemedia.adapter.esign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.porto.api.client.ConfiguredClientAdapter;
import systems.porto.context.Context;
import systems.porto.shinemedia.esign.CreateEnvelopeRequest;
import systems.porto.shinemedia.esign.ESign;
import systems.porto.shinemedia.esign.EnvelopeHandle;
import systems.porto.shinemedia.esign.EnvelopeSigner;
import systems.porto.shinemedia.esign.EnvelopeStatus;
import systems.porto.shinemedia.esign.ESignWebhookRequest;
import systems.porto.shinemedia.provider.SowSignatureSides;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DocuSign e-sign adapter. {@code esign.backend=stub} for local (no DocuSign account);
 * {@code docusign} uses JWT grant + REST envelopes (sandbox or production).
 * Stub files go under {@code ~/tmp/shine-media/esign-stub} (or {@code esign.stub.root});
 * that directory is created only when backend is stub, never under the API working tree.
 *
 * <p>DocuSign emails signers itself. Use {@code email-smtp} for app-side notifications.
 *
 * <p>SignHere tabs use AutoPlace anchors {@code /sig-provider/} and {@code /sig-shine/}
 * which the SoW HTML templates emit at the signature blocks (not absolute page-1 coordinates).
 */
public class ESignDocusignAdapter extends ConfiguredClientAdapter<Context> implements ESign {

    private static final Logger log = LoggerFactory.getLogger(ESignDocusignAdapter.class);
    private static final String ANCHOR_PROVIDER = "/sig-provider/";
    private static final String ANCHOR_SHINE = "/sig-shine/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, ESignStubEnvelope> stubs = new ConcurrentHashMap<>();

    private String backend;
    private Path stubRoot;
    private String integrationKey;
    private String userId;
    private String accountId;
    private String authServer;
    private String baseUri;
    private PrivateKey privateKey;
    private String webhookSecret;
    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    @Override
    public String id() {
        return "e-sign-docusign";
    }

    @Override
    public void init(final Context context) {
        super.init(context);
        this.backend = configOr("esign.backend", "stub").trim().toLowerCase(Locale.ROOT);
        if ("stub".equals(backend)) {
            this.stubRoot = resolveStubRoot();
            try {
                Files.createDirectories(stubRoot);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create esign stub root: " + stubRoot, e);
            }
            this.webhookSecret = configOr("docusign.webhookSecret", "").trim();
            log.info("e-sign-docusign backend=stub root={}", stubRoot);
            return;
        }
        if (!"docusign".equals(backend)) {
            throw new IllegalStateException("esign.backend must be stub or docusign; got " + backend);
        }
        this.integrationKey = requiredConfig("docusign.integrationKey");
        this.userId = requiredConfig("docusign.userId");
        this.accountId = requiredConfig("docusign.accountId");
        this.authServer = configOr("docusign.authServer", "https://account-d.docusign.com");
        this.baseUri = configOr("docusign.baseUri", "https://demo.docusign.net/restapi");
        String pem = configOr("docusign.privateKeyPem", "");
        String pemPath = configOr("docusign.privateKeyPath", "");
        try {
            if (!pem.isBlank()) {
                this.privateKey = parsePkcs8Pem(pem);
            } else if (!pemPath.isBlank()) {
                Path path = Paths.get(pemPath);
                if (!path.isAbsolute()) {
                    path = Paths.get(context.getHomeDirectory(), pemPath);
                }
                this.privateKey = parsePkcs8Pem(Files.readString(path));
            } else {
                throw new IllegalStateException(
                    "docusign.privateKeyPem or docusign.privateKeyPath is required");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load DocuSign private key", e);
        }
        this.webhookSecret = configOr("docusign.webhookSecret", "").trim();
        if (webhookSecret.isBlank()) {
            log.warn("e-sign-docusign docusign.webhookSecret is empty; Connect HMAC will not be verified");
        }
        log.info("e-sign-docusign backend=docusign accountId={} authServer={}", accountId, authServer);
    }

    @Override
    public EnvelopeHandle createEnvelope(final CreateEnvelopeRequest request) {
        if (request == null || request.signers() == null || request.signers().isEmpty()) {
            throw new IllegalArgumentException("createEnvelope requires at least one signer");
        }
        if (request.documentPdf() == null || request.documentPdf().length == 0) {
            throw new IllegalArgumentException("createEnvelope requires documentPdf");
        }
        if ("stub".equals(backend)) {
            return createStub(request);
        }
        return createDocusign(request);
    }

    @Override
    public Optional<EnvelopeStatus> getStatus(final String envelopeId) {
        if (envelopeId == null || envelopeId.isBlank()) {
            return Optional.empty();
        }
        if ("stub".equals(backend)) {
            ESignStubEnvelope stub = stubs.get(envelopeId);
            if (stub == null) {
                stub = loadStubFromDisk(envelopeId);
                if (stub != null) {
                    stubs.put(envelopeId, stub);
                }
            }
            if (stub == null) {
                return Optional.empty();
            }
            return Optional.of(EnvelopeStatus.builder()
                .envelopeId(envelopeId)
                .status(stub.status)
                .completed("completed".equalsIgnoreCase(stub.status))
                .build());
        }
        try {
            String token = accessToken();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUri + "/v2.1/accounts/" + accountId + "/envelopes/" + envelopeId))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() >= 300) {
                throw new RuntimeException("DocuSign getStatus HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            String status = json.path("status").asText("unknown");
            return Optional.of(EnvelopeStatus.builder()
                .envelopeId(envelopeId)
                .status(status)
                .completed("completed".equalsIgnoreCase(status))
                .build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DocuSign getStatus interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("DocuSign getStatus failed", e);
        }
    }

    @Override
    public Optional<byte[]> downloadSignedDocument(final String envelopeId) {
        if (envelopeId == null || envelopeId.isBlank()) {
            return Optional.empty();
        }
        if ("stub".equals(backend)) {
            ESignStubEnvelope stub = stubs.get(envelopeId);
            if (stub == null) {
                stub = loadStubFromDisk(envelopeId);
                if (stub != null) {
                    stubs.put(envelopeId, stub);
                }
            }
            if (stub == null || stub.pdf == null) {
                return Optional.empty();
            }
            return Optional.of(stub.pdf);
        }
        try {
            String token = accessToken();
            String uri = baseUri + "/v2.1/accounts/" + accountId
                + "/envelopes/" + envelopeId + "/documents/combined";
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/pdf")
                .GET()
                .build();
            HttpResponse<byte[]> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() >= 300) {
                throw new RuntimeException("DocuSign downloadSignedDocument HTTP " + response.statusCode());
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DocuSign downloadSignedDocument interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("DocuSign downloadSignedDocument failed", e);
        }
    }

    @Override
    public Optional<EnvelopeStatus> acceptWebhook(final ESignWebhookRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        byte[] raw = request.rawBody() != null ? request.rawBody() : new byte[0];
        DocusignConnectHmac.verify(webhookSecret, raw, request.headers());
        Optional<EnvelopeStatus> parsed = DocusignConnectPayload.parse(raw, request.contentType(), mapper);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        EnvelopeStatus status = parsed.get();
        if ("stub".equals(backend)) {
            ESignStubEnvelope stub = stubs.get(status.envelopeId());
            if (stub == null) {
                stub = loadStubFromDisk(status.envelopeId());
                if (stub != null) {
                    stubs.put(status.envelopeId(), stub);
                }
            }
            if (stub != null) {
                stub.status = "completed";
            }
        }
        log.info("e-sign-docusign connect envelope {} completed", status.envelopeId());
        return parsed;
    }

    /**
     * Local helper: mark a stub envelope completed (simulates DocuSign webhook).
     */
    public void completeStubEnvelope(final String envelopeId) {
        ESignStubEnvelope stub = stubs.get(envelopeId);
        if (stub == null) {
            throw new IllegalArgumentException("Unknown stub envelope: " + envelopeId);
        }
        stub.status = "completed";
        log.info("e-sign-docusign stub envelope {} marked completed", envelopeId);
    }

    private EnvelopeHandle createStub(final CreateEnvelopeRequest request) {
        String id = "stub-" + UUID.randomUUID();
        ESignStubEnvelope stub = new ESignStubEnvelope();
        stub.status = "sent";
        stub.pdf = request.documentPdf();
        stubs.put(id, stub);
        try {
            Path meta = stubRoot.resolve(id + ".json");
            ObjectNode node = mapper.createObjectNode();
            node.put("envelopeId", id);
            node.put("status", stub.status);
            node.put("subject", request.subject());
            ArrayNode signers = node.putArray("signers");
            for (EnvelopeSigner signer : request.signers()) {
                ObjectNode s = signers.addObject();
                s.put("role", signer.role());
                s.put("name", signer.name());
                s.put("email", signer.email());
                s.put("routingOrder", signer.routingOrder());
            }
            Files.writeString(meta, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            Files.write(stubRoot.resolve(id + ".pdf"), request.documentPdf());
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist stub envelope", e);
        }
        log.info("e-sign-docusign stub envelope created id={} signers={}", id, request.signers().size());
        return EnvelopeHandle.builder().envelopeId(id).status("sent").provider("stub").build();
    }

    private EnvelopeHandle createDocusign(final CreateEnvelopeRequest request) {
        try {
            String token = accessToken();
            ObjectNode body = mapper.createObjectNode();
            body.put("emailSubject", request.subject() != null ? request.subject() : "Please sign");
            if (request.emailBlurb() != null) {
                body.put("emailBlurb", request.emailBlurb());
            }
            body.put("status", "sent");

            ArrayNode documents = body.putArray("documents");
            ObjectNode doc = documents.addObject();
            doc.put("documentBase64", Base64.getEncoder().encodeToString(request.documentPdf()));
            doc.put("name", request.documentName() != null ? request.documentName() : "sow.pdf");
            doc.put("fileExtension", "pdf");
            doc.put("documentId", "1");

            ArrayNode recipients = body.putObject("recipients").putArray("signers");
            int i = 1;
            for (EnvelopeSigner signer : request.signers()) {
                ObjectNode s = recipients.addObject();
                s.put("email", signer.email());
                s.put("name", signer.name());
                s.put("recipientId", String.valueOf(i++));
                s.put("routingOrder", String.valueOf(signer.routingOrder() > 0 ? signer.routingOrder() : i));
                addSignHereAnchor(s, signer);
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUri + "/v2.1/accounts/" + accountId + "/envelopes"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new RuntimeException("DocuSign createEnvelope HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            String envelopeId = json.path("envelopeId").asText();
            String status = json.path("status").asText("sent");
            log.info("e-sign-docusign envelope created id={} status={}", envelopeId, status);
            return EnvelopeHandle.builder()
                .envelopeId(envelopeId)
                .status(status)
                .provider("docusign")
                .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DocuSign createEnvelope interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("DocuSign createEnvelope failed", e);
        }
    }

    private static void addSignHereAnchor(final ObjectNode signerNode, final EnvelopeSigner signer) {
        ArrayNode tabs = signerNode.putObject("tabs").putArray("signHereTabs");
        ObjectNode tab = tabs.addObject();
        tab.put("documentId", "1");
        tab.put("anchorString", anchorForRole(signer.role()));
        tab.put("anchorUnits", "pixels");
        tab.put("anchorXOffset", "0");
        tab.put("anchorYOffset", "-12");
        tab.put("anchorIgnoreIfNotPresent", "false");
        tab.put("anchorCaseSensitive", "true");
        tab.put("anchorMatchWholeWord", "false");
    }

    private static String anchorForRole(final String role) {
        if (role != null && SowSignatureSides.SHINE.equalsIgnoreCase(role.trim())) {
            return ANCHOR_SHINE;
        }
        return ANCHOR_PROVIDER;
    }

    private synchronized String accessToken() throws IOException, InterruptedException {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        long now = Instant.now().getEpochSecond();
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("iss", integrationKey);
        payload.put("sub", userId);
        payload.put("iat", now);
        payload.put("exp", now + 3600);
        payload.put("aud", authServer.replace("https://", "").replace("http://", ""));
        payload.put("scope", "signature impersonation");

        String encodedHeader = b64Url(mapper.writeValueAsBytes(header));
        String encodedPayload = b64Url(mapper.writeValueAsBytes(payload));
        String signingInput = encodedHeader + "." + encodedPayload;
        String signature;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            signature = b64Url(signer.sign());
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign DocuSign JWT", e);
        }
        String jwt = signingInput + "." + signature;
        String form = "grant_type=" + URLEncoder.encode(
            "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        HttpRequest tokenRequest = HttpRequest.newBuilder()
            .uri(URI.create(authServer + "/oauth/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        HttpResponse<String> response = http.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new RuntimeException("DocuSign token HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode json = mapper.readTree(response.body());
        cachedToken = json.path("access_token").asText();
        int expiresIn = json.path("expires_in").asInt(3600);
        cachedTokenExpiry = Instant.now().plusSeconds(expiresIn);
        return cachedToken;
    }

    private Path resolveStubRoot() {
        String root = configOr("esign.stub.root", "").trim();
        if (root.isEmpty()) {
            return defaultStubRoot();
        }
        if (root.startsWith("~/") || "~".equals(root)) {
            return Paths.get(root.replaceFirst("^~", System.getProperty("user.home"))).normalize();
        }
        Path path = Paths.get(root);
        if (!path.isAbsolute()) {
            log.warn(
                "esign.stub.root must be absolute or ~/...; ignoring relative {} and using {}",
                root,
                defaultStubRoot());
            return defaultStubRoot();
        }
        return path.normalize();
    }

    private static Path defaultStubRoot() {
        return Paths.get(System.getProperty("user.home"), "tmp", "shine-media", "esign-stub");
    }

    private static String b64Url(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static PrivateKey parsePkcs8Pem(final String pem) {
        String normalized = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "DocuSign private key must be PKCS#8 PEM (BEGIN PRIVATE KEY). "
                    + "Convert PKCS#1 with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem",
                e);
        }
    }

    private ESignStubEnvelope loadStubFromDisk(final String envelopeId) {
        try {
            Path pdf = stubRoot.resolve(envelopeId + ".pdf");
            Path meta = stubRoot.resolve(envelopeId + ".json");
            if (!Files.isRegularFile(pdf)) {
                return null;
            }
            ESignStubEnvelope stub = new ESignStubEnvelope();
            stub.pdf = Files.readAllBytes(pdf);
            stub.status = "sent";
            if (Files.isRegularFile(meta)) {
                JsonNode json = mapper.readTree(Files.readString(meta));
                stub.status = json.path("status").asText("sent");
            }
            return stub;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load stub envelope " + envelopeId, e);
        }
    }
}
