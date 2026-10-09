package systems.porto.shinemedia.adapter.email;

import jakarta.activation.CommandMap;
import jakarta.activation.MailcapCommandMap;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.porto.api.client.ConfiguredClientAdapter;
import systems.porto.context.Context;
import systems.porto.shinemedia.email.EmailAttachment;
import systems.porto.shinemedia.email.EmailSender;
import systems.porto.shinemedia.email.OutboundEmail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * SMTP email for app notifications. Dev: Outlook.com SMTP (or Mailpit on localhost:1025).
 * Prod: SES SMTP or any SMTP relay.
 */
public class EmailSmtpAdapter extends ConfiguredClientAdapter<Context> implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSmtpAdapter.class);

    private Session session;
    private String fromAddress;
    private boolean enabled;
    private String redirectTo;

    @Override
    public String id() {
        return "email-smtp";
    }

    @Override
    public void init(final Context context) {
        super.init(context);
        this.enabled = Boolean.parseBoolean(configOr("smtp.enabled", "true"));
        this.fromAddress = configOr("smtp.from", "noreply@shinemedia.local").trim();
        String redirect = configOr("smtp.redirectTo", "");
        this.redirectTo = redirect.isBlank() ? null : redirect.trim();
        String host = configOr("smtp.host", "localhost");
        String port = configOr("smtp.port", "1025");
        String username = configOr("smtp.username", "").trim();
        String password = resolvePassword(context);
        boolean startTls = Boolean.parseBoolean(configOr("smtp.startTls", "false"));
        boolean auth = !username.isBlank();
        if (auth && password.isBlank()) {
            log.warn("email-smtp username is set but password is empty; "
                + "set smtp.password, smtp.passwordFile, or SHINE_SMTP_PASSWORD");
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        if (startTls) {
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        }
        if (auth) {
            this.session = Session.getInstance(props, new SmtpPasswordAuthenticator(username, password));
        } else {
            this.session = Session.getInstance(props);
        }
        log.info("email-smtp enabled={} host={}:{} from={} redirectTo={}",
            enabled, host, port, fromAddress, redirectTo);
    }

    private String resolvePassword(final Context context) {
        String password = configOr("smtp.password", "");
        if (!password.isBlank()) {
            return password;
        }
        String passwordFile = configOr("smtp.passwordFile", "");
        if (!passwordFile.isBlank()) {
            Path path = Paths.get(passwordFile);
            if (!path.isAbsolute()) {
                path = Paths.get(context.getHomeDirectory(), passwordFile);
            }
            if (Files.isRegularFile(path)) {
                try {
                    return Files.readString(path).strip();
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read smtp.passwordFile " + path, e);
                }
            }
            log.warn("email-smtp smtp.passwordFile not found: {}", path.toAbsolutePath());
        }
        String envPassword = System.getenv("SHINE_SMTP_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            return envPassword;
        }
        return "";
    }

    @Override
    public void send(final OutboundEmail email) {
        if (!enabled) {
            log.info("email-smtp disabled; skip send to={} subject={}", email.to(), email.subject());
            return;
        }
        if (email.to() == null || email.to().isBlank()) {
            throw new IllegalArgumentException("email.to is required");
        }
        if (email.subject() == null || email.subject().isBlank()) {
            throw new IllegalArgumentException("email.subject is required");
        }
        try {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            CommandMap previousMap = CommandMap.getDefaultCommandMap();
            try {
                Thread.currentThread().setContextClassLoader(EmailSmtpAdapter.class.getClassLoader());
                CommandMap.setDefaultCommandMap(new MailcapCommandMap());
                sendMessage(email);
            } finally {
                CommandMap.setDefaultCommandMap(previousMap);
                Thread.currentThread().setContextClassLoader(previous);
            }
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email to " + email.to(), e);
        }
    }

    private void sendMessage(final OutboundEmail email) throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress));
        String to = email.to();
        if (redirectTo != null) {
            message.setHeader("X-Original-To", to);
            if (email.cc() != null && !email.cc().isEmpty()) {
                message.setHeader("X-Original-Cc", String.join(",", email.cc()));
            }
            log.info("email-smtp redirect to={} originalTo={}", redirectTo, to);
            to = redirectTo;
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        if (redirectTo == null && email.cc() != null && !email.cc().isEmpty()) {
            message.setRecipients(
                Message.RecipientType.CC,
                InternetAddress.parse(String.join(",", email.cc()), false));
        }
        message.setSubject(email.subject(), "UTF-8");
        if (email.attachments() != null && !email.attachments().isEmpty()) {
            message.setContent(multipartWithAttachments(email));
        } else if (email.htmlBody() != null && !email.htmlBody().isBlank()) {
            message.setContent(email.htmlBody(), "text/html; charset=UTF-8");
        } else {
            message.setText(email.textBody() != null ? email.textBody() : "", "UTF-8");
        }
        message.saveChanges();
        Transport.send(message);
        log.info("email-smtp sent to={} subject={}", to, email.subject());
    }

    private static MimeMultipart multipartWithAttachments(final OutboundEmail email) throws MessagingException {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        if (email.htmlBody() != null && !email.htmlBody().isBlank()) {
            body.setContent(email.htmlBody(), "text/html; charset=UTF-8");
        } else {
            body.setText(email.textBody() != null ? email.textBody() : "", "UTF-8");
        }
        mixed.addBodyPart(body);
        for (EmailAttachment attachment : email.attachments()) {
            if (attachment == null || attachment.content() == null) {
                continue;
            }
            String contentType = attachment.contentType() != null && !attachment.contentType().isBlank()
                ? attachment.contentType()
                : "application/octet-stream";
            MimeBodyPart part = new MimeBodyPart();
            part.setDataHandler(new jakarta.activation.DataHandler(
                new ByteArrayDataSource(attachment.content(), contentType)));
            part.setFileName(attachment.fileName() != null ? attachment.fileName() : "attachment");
            part.setDisposition(Part.ATTACHMENT);
            mixed.addBodyPart(part);
        }
        return mixed;
    }
}
