package systems.porto.shinemedia.adapter.email;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;

final class SmtpPasswordAuthenticator extends Authenticator {

    private final String username;
    private final String password;

    SmtpPasswordAuthenticator(final String username, final String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(username, password);
    }
}
