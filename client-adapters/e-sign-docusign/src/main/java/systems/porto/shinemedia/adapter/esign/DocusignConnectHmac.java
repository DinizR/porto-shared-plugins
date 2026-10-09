package systems.porto.shinemedia.adapter.esign;

import systems.porto.api.spi.BadRequestException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DocuSign Connect HMAC-SHA256 verification ({@code X-DocuSign-Signature-N} headers).
 */
final class DocusignConnectHmac {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String HEADER_PREFIX = "x-docusign-signature-";

    private DocusignConnectHmac() {
    }

    static void verify(final String secret, final byte[] rawBody, final Map<String, String> headers) {
        if (secret == null || secret.isBlank()) {
            return;
        }
        byte[] body = rawBody != null ? rawBody : new byte[0];
        String expected = sign(secret, body);
        List<String> provided = signatureHeaders(headers);
        if (provided.isEmpty()) {
            throw new BadRequestException("DocuSign Connect HMAC header X-DocuSign-Signature-1 is required");
        }
        boolean matched = false;
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        for (String signature : provided) {
            if (signature == null || signature.isBlank()) {
                continue;
            }
            byte[] actual = signature.trim().getBytes(StandardCharsets.US_ASCII);
            matched |= MessageDigest.isEqual(expectedBytes, actual);
        }
        if (!matched) {
            throw new BadRequestException("DocuSign Connect HMAC signature is invalid");
        }
    }

    static String sign(final String secret, final byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(rawBody != null ? rawBody : new byte[0]));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute DocuSign Connect HMAC", e);
        }
    }

    private static List<String> signatureHeaders(final Map<String, String> headers) {
        List<String> values = new ArrayList<>();
        if (headers == null) {
            return values;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String name = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (name.startsWith(HEADER_PREFIX)) {
                values.add(entry.getValue());
            }
        }
        return values;
    }
}
