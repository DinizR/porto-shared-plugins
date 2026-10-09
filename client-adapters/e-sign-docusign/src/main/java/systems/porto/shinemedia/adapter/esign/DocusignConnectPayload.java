package systems.porto.shinemedia.adapter.esign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import systems.porto.shinemedia.esign.EnvelopeStatus;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import org.w3c.dom.Document;

/**
 * Parses DocuSign Connect JSON (SIM) and classic XML notifications.
 */
final class DocusignConnectPayload {

    private static final String EVENT_ENVELOPE_COMPLETED = "envelope-completed";

    private DocusignConnectPayload() {
    }

    static Optional<EnvelopeStatus> parse(
        final byte[] rawBody,
        final String contentType,
        final ObjectMapper mapper
    ) {
        if (rawBody == null || rawBody.length == 0) {
            return Optional.empty();
        }
        String type = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        if (type.contains("xml") || looksLikeXml(rawBody)) {
            Optional<EnvelopeStatus> xml = parseXml(rawBody);
            if (xml.isPresent()) {
                return xml;
            }
        }
        if (type.contains("json") || looksLikeJson(rawBody) || type.isBlank()) {
            return parseJson(rawBody, mapper);
        }
        return Optional.empty();
    }

    static Optional<EnvelopeStatus> fromJson(final JsonNode json) {
        if (json == null || json.isMissingNode() || json.isNull()) {
            return Optional.empty();
        }
        String envelopeId = firstNonBlank(
            text(json, "data", "envelopeId"),
            text(json, "data", "envelopeSummary", "envelopeId"),
            text(json, "envelopeId"),
            text(json, "EnvelopeID")
        );
        if (envelopeId == null) {
            return Optional.empty();
        }
        String event = firstNonBlank(text(json, "event"), "");
        String status = firstNonBlank(
            text(json, "status"),
            text(json, "data", "envelopeSummary", "status"),
            event
        );
        if (!isCompleted(event, status)) {
            return Optional.empty();
        }
        return Optional.of(EnvelopeStatus.builder()
            .envelopeId(envelopeId)
            .status(status != null ? status : EVENT_ENVELOPE_COMPLETED)
            .completed(true)
            .build());
    }

    private static Optional<EnvelopeStatus> parseJson(final byte[] rawBody, final ObjectMapper mapper) {
        try {
            return fromJson(mapper.readTree(rawBody));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<EnvelopeStatus> parseXml(final byte[] rawBody) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(rawBody));
            XPath xpath = XPathFactory.newInstance().newXPath();
            String envelopeId = string(xpath, document,
                "//*[local-name()='EnvelopeStatus']/*[local-name()='EnvelopeID']");
            String status = string(xpath, document,
                "//*[local-name()='EnvelopeStatus']/*[local-name()='Status']");
            if (envelopeId == null || envelopeId.isBlank() || !isCompleted("", status)) {
                return Optional.empty();
            }
            return Optional.of(EnvelopeStatus.builder()
                .envelopeId(envelopeId.trim())
                .status(status.trim())
                .completed(true)
                .build());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isCompleted(final String event, final String status) {
        if (EVENT_ENVELOPE_COMPLETED.equalsIgnoreCase(event != null ? event.trim() : "")) {
            return true;
        }
        return status != null && "completed".equalsIgnoreCase(status.trim());
    }

    private static String text(final JsonNode json, final String... path) {
        JsonNode node = json;
        for (String segment : path) {
            node = node.path(segment);
        }
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(final String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String string(final XPath xpath, final Document document, final String expression)
        throws Exception {
        Object value = xpath.evaluate(expression, document, XPathConstants.STRING);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static boolean looksLikeXml(final byte[] rawBody) {
        String prefix = prefix(rawBody);
        return prefix.startsWith("<") || prefix.startsWith("<?xml");
    }

    private static boolean looksLikeJson(final byte[] rawBody) {
        String prefix = prefix(rawBody);
        return prefix.startsWith("{") || prefix.startsWith("[");
    }

    private static String prefix(final byte[] rawBody) {
        int length = Math.min(rawBody.length, 64);
        return new String(rawBody, 0, length, StandardCharsets.UTF_8).stripLeading();
    }
}
