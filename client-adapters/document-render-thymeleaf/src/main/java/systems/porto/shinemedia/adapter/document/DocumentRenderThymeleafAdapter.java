package systems.porto.shinemedia.adapter.document;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import systems.porto.api.client.ConfiguredClientAdapter;
import systems.porto.shinemedia.document.DocumentRenderer;
import systems.porto.shinemedia.document.InsertionSowDocumentModel;
import systems.porto.shinemedia.document.ProviderSowClauseFlags;
import systems.porto.shinemedia.document.ProviderSowDocumentModel;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Processes inline Thymeleaf HTML from {@code DocumentStorage} to PDF or HTML.
 */
public class DocumentRenderThymeleafAdapter extends ConfiguredClientAdapter<systems.porto.context.Context>
    implements DocumentRenderer {

    private static final Logger log = LoggerFactory.getLogger(DocumentRenderThymeleafAdapter.class);

    private TemplateEngine stringEngine;

    @Override
    public String id() {
        return "document-render-thymeleaf";
    }

    @Override
    public void init(final systems.porto.context.Context context) {
        super.init(context);
        StringTemplateResolver stringResolver = new StringTemplateResolver();
        stringResolver.setTemplateMode(TemplateMode.HTML);
        stringResolver.setCacheable(false);
        stringEngine = new TemplateEngine();
        stringEngine.setTemplateResolver(stringResolver);
        log.info("document-render-thymeleaf ready (inline HTML from DocumentStorage)");
    }

    @Override
    public byte[] renderPdfFromHtml(final String html, final Object model) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("html is required");
        }
        if (model == null) {
            throw new IllegalArgumentException("model is required");
        }
        String processed;
        try {
            processed = stringEngine.process(html, thymeleafContext(model));
        } catch (Exception e) {
            throw new RuntimeException("Failed to process uploaded HTML template", e);
        }
        return htmlToPdf(processed, "inline");
    }

    @Override
    public String renderHtmlFromHtml(final String html, final Object model) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("html is required");
        }
        if (model == null) {
            throw new IllegalArgumentException("model is required");
        }
        try {
            return stringEngine.process(html, thymeleafContext(model));
        } catch (Exception e) {
            throw new RuntimeException("Failed to process HTML template", e);
        }
    }

    private Context thymeleafContext(final Object model) {
        Context thymeleaf = new Context(localeFrom(model));
        if (model instanceof ProviderSowDocumentModel doc) {
            ProviderSowClauseFlags clauses = ProviderSowClauseFlags.from(doc.lines());
            thymeleaf.setVariable("company", doc.company());
            thymeleaf.setVariable("provider", doc.provider());
            thymeleaf.setVariable("sow", doc.sow());
            thymeleaf.setVariable("template", doc.template());
            thymeleaf.setVariable("lines", doc.lines());
            thymeleaf.setVariable("documentTitle", doc.documentTitle());
            thymeleaf.setVariable("generatedAt", doc.generatedAt());
            thymeleaf.setVariable("providerResponsibleName", doc.providerResponsibleName());
            thymeleaf.setVariable("providerResponsibleEmail", doc.providerResponsibleEmail());
            thymeleaf.setVariable("personalMessage", doc.personalMessage());
            thymeleaf.setVariable("proposalPdfFileName", doc.proposalPdfFileName());
            thymeleaf.setVariable("salesManagerName", doc.salesManagerName());
            thymeleaf.setVariable("salesManagerEmail", doc.salesManagerEmail());
            thymeleaf.setVariable("salesManagerPhone", doc.salesManagerPhone());
            thymeleaf.setVariable("youtubeOnly", clauses.youtubeOnly());
            thymeleaf.setVariable("notYoutubeOnly", clauses.notYoutubeOnly());
            thymeleaf.setVariable("doohMinutePriced", clauses.doohMinutePriced());
            thymeleaf.setVariable("genericInsertion", clauses.genericInsertion());
        } else if (model instanceof InsertionSowDocumentModel doc) {
            thymeleaf.setVariable("company", doc.company());
            thymeleaf.setVariable("advertiser", doc.advertiser());
            thymeleaf.setVariable("sow", doc.sow());
            thymeleaf.setVariable("template", doc.template());
            thymeleaf.setVariable("lines", doc.lines());
            thymeleaf.setVariable("documentTitle", doc.documentTitle());
            thymeleaf.setVariable("generatedAt", doc.generatedAt());
            thymeleaf.setVariable("advertiserResponsibleName", doc.advertiserResponsibleName());
            thymeleaf.setVariable("advertiserResponsibleEmail", doc.advertiserResponsibleEmail());
            thymeleaf.setVariable("personalMessage", doc.personalMessage());
            thymeleaf.setVariable("proposalPdfFileName", doc.proposalPdfFileName());
            thymeleaf.setVariable("salesManagerName", doc.salesManagerName());
            thymeleaf.setVariable("salesManagerEmail", doc.salesManagerEmail());
            thymeleaf.setVariable("salesManagerPhone", doc.salesManagerPhone());
            thymeleaf.setVariable("taxName", doc.taxName());
            thymeleaf.setVariable("taxDescription", doc.taxDescription());
            thymeleaf.setVariable("campaignWeeks", doc.campaignWeeks());
            thymeleaf.setVariable("proposalSubtotal", doc.proposalSubtotal());
            thymeleaf.setVariable("proposalDiscount", doc.proposalDiscount());
            thymeleaf.setVariable("proposalNet", doc.proposalNet());
            thymeleaf.setVariable("proposalTaxAmount", doc.proposalTaxAmount());
            thymeleaf.setVariable("proposalTotal", doc.proposalTotal());
        } else {
            thymeleaf.setVariable("model", model);
        }
        return thymeleaf;
    }

    private byte[] htmlToPdf(final String html, final String label) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            byte[] pdf = out.toByteArray();
            log.info("document-render-thymeleaf rendered template={} bytes={}", label, pdf.length);
            return pdf;
        } catch (Exception e) {
            throw new RuntimeException("Failed to render PDF for template " + label, e);
        }
    }

    private static Locale localeFrom(final Object model) {
        if (model instanceof ProviderSowDocumentModel doc
            && doc.template() != null
            && doc.template().locale() != null
            && !doc.template().locale().isBlank()) {
            return Locale.forLanguageTag(doc.template().locale().trim().replace('_', '-'));
        }
        if (model instanceof InsertionSowDocumentModel doc
            && doc.template() != null
            && doc.template().locale() != null
            && !doc.template().locale().isBlank()) {
            return Locale.forLanguageTag(doc.template().locale().trim().replace('_', '-'));
        }
        return Locale.ROOT;
    }
}
