package systems.porto.shinemedia.adapter.idp;

import systems.porto.api.auth.AccessToken;
import systems.porto.api.auth.AuthenticationGrant;
import systems.porto.api.auth.AuthenticationRequest;
import systems.porto.api.auth.AuthenticationResult;
import systems.porto.api.auth.AuthorizationSource;
import systems.porto.api.auth.Identity;
import systems.porto.api.auth.IdentityAccount;
import systems.porto.api.auth.IdentityProvider;
import systems.porto.api.auth.IdentityProviderCapabilities;
import systems.porto.api.auth.IdentityProviderKind;
import systems.porto.api.auth.IdentityStore;
import systems.porto.api.auth.TokenIssueOptions;
import systems.porto.api.auth.TokenValidationResult;
import systems.porto.api.auth.UnauthorizedException;
import systems.porto.api.client.ConfiguredClientAdapter;
import systems.porto.api.spi.BadRequestException;
import systems.porto.api.spi.HostContext;
import systems.porto.context.Context;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local users + host-issued JWT. Swap this plugin for a Keycloak IdP later
 * by changing {@code porto.api.auth.identity-provider}.
 */
public class LocalIdentityProviderAdapter extends ConfiguredClientAdapter<Context> implements IdentityProvider {

    private IdentityStore identityStore;
    private AuthorizationSource authorizationSource;

    @Override
    public String id() {
        return "local-idp";
    }

    @Override
    public void init(final Context context) {
        super.init(context);
        if (!(context instanceof HostContext host)) {
            throw new IllegalStateException("Host context is required for local-idp");
        }
        this.identityStore = capability(host, configOr("store.adapter", "identity-store-jdbc"), IdentityStore.class);
        this.authorizationSource = capability(
            host,
            configOr("authorization.adapter", "identity-store-jdbc"),
            AuthorizationSource.class
        );
    }

    @Override
    public IdentityProviderKind kind() {
        return IdentityProviderKind.LOCAL;
    }

    @Override
    public IdentityProviderCapabilities capabilities() {
        return IdentityProviderCapabilities.localJwt();
    }

    @Override
    public AuthenticationResult authenticate(final AuthenticationRequest request) {
        if (request == null || request.grant() == null) {
            throw new UnauthorizedException("Authentication grant is required");
        }
        if (request.grant() == AuthenticationGrant.ACCESS_TOKEN) {
            TokenValidationResult validation = validateAccessToken(request.rawAccessToken());
            if (!validation.valid()) {
                throw new UnauthorizedException(validation.failureReason());
            }
            return AuthenticationResult.of(validation.identity(), host().accessTokenService().issue(
                validation.identity(),
                tokenOptions()
            ));
        }
        if (request.grant() != AuthenticationGrant.PASSWORD) {
            throw new UnauthorizedException("Unsupported grant: " + request.grant());
        }
        if (request.username() == null || request.username().isBlank()
            || request.secret() == null || request.secret().isBlank()) {
            throw new UnauthorizedException("Username and password are required");
        }
        IdentityAccount account = identityStore.findByUsername(request.username())
            .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!account.enabled()) {
            throw new UnauthorizedException("Account is disabled");
        }
        if (!identityStore.verifySecret(request.username(), request.secret())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        Identity identity = identityOf(account);
        AccessToken accessToken = host().accessTokenService().issue(identity, tokenOptions());
        return AuthenticationResult.of(identity, accessToken);
    }

    @Override
    public TokenValidationResult validateAccessToken(final String rawToken) {
        TokenValidationResult result = host().accessTokenService().validate(rawToken);
        if (!result.valid() || result.identity() == null) {
            return result;
        }
        IdentityAccount account = identityStore.findBySubject(result.identity().subject())
            .orElse(null);
        if (account == null) {
            return TokenValidationResult.invalid("unknown_subject");
        }
        if (!account.enabled()) {
            return TokenValidationResult.invalid("account_disabled");
        }
        return TokenValidationResult.valid(identityOf(account));
    }

    @Override
    public void changePassword(final String username, final String currentSecret, final String newSecret) {
        if (username == null || username.isBlank()) {
            throw new UnauthorizedException("Authentication is required");
        }
        if (currentSecret == null || currentSecret.isBlank() || newSecret == null || newSecret.isBlank()) {
            throw new BadRequestException("Current and new password are required");
        }
        if (newSecret.length() < 8) {
            throw new BadRequestException("New password must be at least 8 characters");
        }
        if (currentSecret.equals(newSecret)) {
            throw new BadRequestException("New password must be different from the current password");
        }
        IdentityAccount account = identityStore.findByUsername(username)
            .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!account.enabled()) {
            throw new UnauthorizedException("Account is disabled");
        }
        if (!identityStore.verifySecret(username, currentSecret)) {
            throw new UnauthorizedException("Invalid credentials");
        }
        identityStore.replaceSecret(username, host().secretHasher().hash(newSecret));
    }

    private Identity identityOf(final IdentityAccount account) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("password_expired", Boolean.toString(account.passwordExpired()));
        if (account.passwordExpiresAt() != null) {
            attributes.put("password_expires_at", account.passwordExpiresAt().toString());
        }
        return new Identity(
            account.subject(),
            account.username(),
            account.email(),
            authorizationSource.profilesFor(account.subject()),
            authorizationSource.permissionsFor(account.subject()),
            attributes
        );
    }

    private TokenIssueOptions tokenOptions() {
        String ttl = configOr("token.ttl", "");
        Duration duration = ttl.isBlank() ? null : Duration.parse(ttl);
        return new TokenIssueOptions(duration, null, Map.of());
    }

    private HostContext host() {
        if (getContext() instanceof HostContext host) {
            return host;
        }
        throw new IllegalStateException("Host context is required for local-idp");
    }

    private static <T> T capability(final HostContext host, final String adapterId, final Class<T> type) {
        Object adapter = host.findClientAdapter(adapterId);
        if (!type.isInstance(adapter)) {
            throw new IllegalStateException(
                "Adapter '" + adapterId + "' does not implement " + type.getName()
            );
        }
        return type.cast(adapter);
    }
}
