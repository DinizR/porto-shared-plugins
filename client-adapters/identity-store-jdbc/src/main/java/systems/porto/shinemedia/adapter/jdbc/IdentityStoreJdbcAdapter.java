package systems.porto.shinemedia.adapter.jdbc;

import systems.porto.api.auth.AuthorizationSource;
import systems.porto.api.auth.IdentityAccount;
import systems.porto.api.auth.IdentityStore;
import systems.porto.api.spi.HostContext;
import systems.porto.api.sql.AbstractJdbcClientAdapter;
import systems.porto.context.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Local identity and authorization over JDBC. SQL is not in this class: each
 * application maps its own tables in
 * {@code plugins/client-adapters/{app}/identity-store-jdbc-{env}.yaml}.
 *
 * <p>Required statement names and result columns are documented in
 * {@code porto-shared-plugins/docs/plugins/identity-store-jdbc.md}.
 */
public class IdentityStoreJdbcAdapter extends AbstractJdbcClientAdapter<Context>
    implements IdentityStore, AuthorizationSource {

    private static final String FIND_BY_USERNAME = "FIND_IDENTITY_BY_USERNAME";
    private static final String FIND_BY_SUBJECT = "FIND_IDENTITY_BY_SUBJECT";
    private static final String FIND_SECRET_HASH = "FIND_IDENTITY_SECRET_HASH";
    private static final String REPLACE_SECRET = "REPLACE_IDENTITY_SECRET";
    private static final String LIST_PROFILES = "LIST_IDENTITY_PROFILES";
    private static final String LIST_PERMISSIONS = "LIST_IDENTITY_PERMISSIONS";

    @Override
    public String id() {
        return "identity-store-jdbc";
    }

    @Override
    protected String[] requiredStatements() {
        return new String[] {
            FIND_BY_USERNAME, FIND_BY_SUBJECT, FIND_SECRET_HASH, REPLACE_SECRET,
            LIST_PROFILES, LIST_PERMISSIONS
        };
    }

    @Override
    public Optional<IdentityAccount> findByUsername(final String username) {
        return find(FIND_BY_USERNAME, username);
    }

    @Override
    public Optional<IdentityAccount> findBySubject(final String subject) {
        return find(FIND_BY_SUBJECT, subject);
    }

    @Override
    public boolean verifySecret(final String username, final String secret) {
        if (username == null || username.isBlank() || secret == null) {
            return false;
        }
        String hash = loadSecretHash(username);
        if (hash == null || hash.isBlank()) {
            return false;
        }
        if (!(getContext() instanceof HostContext host)) {
            throw new IllegalStateException("Host context is required to verify secrets");
        }
        return host.secretHasher().matches(secret, hash);
    }

    @Override
    public void replaceSecret(final String username, final String newHash) {
        if (username == null || username.isBlank() || newHash == null || newHash.isBlank()) {
            throw new IllegalArgumentException("Username and password hash are required");
        }
        try (Connection connection = dataSource().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql(REPLACE_SECRET))) {
            ps.setString(1, newHash);
            ps.setString(2, username);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("No local account for username " + username);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to replace secret for " + username, e);
        }
    }

    @Override
    public Set<String> profilesFor(final String subject) {
        return loadNames(LIST_PROFILES, subject);
    }

    @Override
    public Set<String> permissionsFor(final String subject) {
        return loadNames(LIST_PERMISSIONS, subject);
    }

    private Optional<IdentityAccount> find(final String statement, final String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql(statement))) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Integer id = (Integer) rs.getObject("id");
                String status = rs.getString("status");
                boolean enabled = status == null
                    || "ACTIVE".equalsIgnoreCase(status)
                    || "ENABLED".equalsIgnoreCase(status);
                Timestamp expiresAt = rs.getTimestamp("password_expires_at");
                Instant passwordExpiresAt = expiresAt == null ? null : expiresAt.toInstant();
                return Optional.of(new IdentityAccount(
                    String.valueOf(id),
                    rs.getString("username"),
                    rs.getString("email"),
                    enabled,
                    passwordExpiresAt,
                    Map.of()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load identity " + key, e);
        }
    }

    private String loadSecretHash(final String username) {
        try (Connection connection = dataSource().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql(FIND_SECRET_HASH))) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("password_hash") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load secret hash for " + username, e);
        }
    }

    private Set<String> loadNames(final String statement, final String subject) {
        if (subject == null || subject.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        try (Connection connection = dataSource().getConnection();
             PreparedStatement ps = connection.prepareStatement(sql(statement))) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isBlank()) {
                        values.add(value);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load authorization for " + subject, e);
        }
        return values;
    }
}
