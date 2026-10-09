package systems.porto.api.adapter.idp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import systems.porto.api.auth.AccessToken;
import systems.porto.api.auth.AuthenticationRequest;
import systems.porto.api.auth.AuthenticationResult;
import systems.porto.api.auth.AuthorizationSource;
import systems.porto.api.auth.ExternalTokenConstraints;
import systems.porto.api.auth.Identity;
import systems.porto.api.auth.IdentityAccount;
import systems.porto.api.auth.IdentityProvider;
import systems.porto.api.auth.IdentityProviderCapabilities;
import systems.porto.api.auth.IdentityProviderKind;
import systems.porto.api.auth.IdentityStore;
import systems.porto.api.auth.RefreshToken;
import systems.porto.api.auth.TokenValidationResult;
import systems.porto.api.auth.UnauthorizedException;
import systems.porto.api.client.ConfiguredClientAdapter;
import systems.porto.api.spi.HostContext;
import systems.porto.context.Context;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * EXTERNAL IdentityProvider: tokens are issued and signed by Keycloak.
 * Profiles and permissions are reloaded from the application store when present.
 */
public class KeycloakIdentityProviderAdapter extends ConfiguredClientAdapter<Context> implements IdentityProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private IdentityStore identityStore;
    private AuthorizationSource authorizationSource;

    @Override
    public String id() {
        return "keycloak-idp";
    }

    @Override
    public void init(final Context context) {
        super.init(context);
        if (!(context instanceof HostContext host)) {
            throw new IllegalStateException("Host context is required for keycloak-idp");
        }
        String storeId = configOr("store.adapter", "identity-store-jdbc");
        String authorizationId = configOr("authorization.adapter", "identity-store-jdbc");
        this.identityStore = optionalCapability(host, storeId, IdentityStore.class);
        this.authorizationSource = optionalCapability(host, authorizationId, AuthorizationSource.class);
    }

    @Override
    public IdentityProviderKind kind() {
        return IdentityProviderKind.EXTERNAL;
    }

    @Override
    public IdentityProviderCapabilities capabilities() {
        if ("false".equalsIgnoreCase(configOr("password.grant", "true"))) {
            return IdentityProviderCapabilities.externalIdp();
        }
        return IdentityProviderCapabilities.externalIdpWithPassword();
    }

    @Override
    public AuthenticationResult authenticate(final AuthenticationRequest request) {
        if (request == null || request.grant() == null) {
            throw new UnauthorizedException("Authentication grant is required");
        }
        return switch (request.grant()) {
            case PASSWORD -> password(request);
            case REFRESH_TOKEN -> refresh(request);
            case AUTHORIZATION_CODE -> authorizationCode(request);
            case ACCESS_TOKEN -> accessToken(request);
        };
    }

    @Override
    public TokenValidationResult validateAccessToken(final String rawToken) {
        TokenValidationResult result = host().accessTokenService().validateExternal(rawToken, constraints());
        if (!result.valid() || result.identity() == null) {
            return result;
        }
        return resolveIdentity(result.identity());
    }

    private AuthenticationResult password(final AuthenticationRequest request) {
        if (request.username() == null || request.username().isBlank()
            || request.secret() == null || request.secret().isBlank()) {
            throw new UnauthorizedException("Username and password are required");
        }
        Map<String, String> form = baseClientForm();
        form.put("grant_type", "password");
        form.put("username", request.username());
        form.put("password", request.secret());
        form.put("scope", "openid");
        return fromTokenResponse(postToken(form));
    }

    private AuthenticationResult refresh(final AuthenticationRequest request) {
        if (request.rawRefreshToken() == null || request.rawRefreshToken().isBlank()) {
            throw new UnauthorizedException("refresh_token is required");
        }
        Map<String, String> form = baseClientForm();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", request.rawRefreshToken());
        return fromTokenResponse(postToken(form));
    }

    private AuthenticationResult authorizationCode(final AuthenticationRequest request) {
        if (request.authorizationCode() == null || request.authorizationCode().isBlank()) {
            throw new UnauthorizedException("authorization code is required");
        }
        Map<String, String> form = baseClientForm();
        form.put("grant_type", "authorization_code");
        form.put("code", request.authorizationCode());
        if (request.redirectUri() != null && !request.redirectUri().isBlank()) {
            form.put("redirect_uri", request.redirectUri());
        }
        return fromTokenResponse(postToken(form));
    }

    private AuthenticationResult accessToken(final AuthenticationRequest request) {
        TokenValidationResult validation = validateAccessToken(request.rawAccessToken());
        if (!validation.valid() || validation.identity() == null) {
            throw new UnauthorizedException(validation.failureReason());
        }
        return AuthenticationResult.of(validation.identity(), AccessToken.bearer(
            request.rawAccessToken(), Instant.now(), Instant.now().plusSeconds(60), requiredConfig("issuer")
        ));
    }

    private AuthenticationResult fromTokenResponse(final JsonNode body) {
        String accessToken = text(body, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new UnauthorizedException("Keycloak did not return an access token");
        }
        TokenValidationResult validation = validateAccessToken(accessToken);
        if (!validation.valid() || validation.identity() == null) {
            throw new UnauthorizedException(validation.failureReason());
        }
        long expiresIn = body.path("expires_in").asLong(300);
        Instant issuedAt = Instant.now();
        AccessToken token = AccessToken.bearer(
            accessToken, issuedAt, issuedAt.plusSeconds(expiresIn), requiredConfig("issuer")
        );
        String refreshValue = text(body, "refresh_token");
        RefreshToken refreshToken = null;
        if (refreshValue != null && !refreshValue.isBlank()) {
            long refreshExpires = body.path("refresh_expires_in").asLong(0);
            Instant refreshExpiry = refreshExpires > 0 ? issuedAt.plusSeconds(refreshExpires) : null;
            refreshToken = new RefreshToken(refreshValue, refreshExpiry);
        }
        return new AuthenticationResult(validation.identity(), token, refreshToken);
    }

    private TokenValidationResult resolveIdentity(final Identity tokenIdentity) {
        if (identityStore == null) {
            return TokenValidationResult.valid(tokenIdentity);
        }
        IdentityAccount account = identityStore.findByUsername(tokenIdentity.username()).orElse(null);
        if (account == null) {
            return TokenValidationResult.valid(tokenIdentity);
        }
        if (!account.enabled()) {
            return TokenValidationResult.invalid("account_disabled");
        }
        Set<String> profiles = authorizationSource != null
            ? authorizationSource.profilesFor(account.subject())
            : tokenIdentity.profiles();
        Set<String> permissions = authorizationSource != null
            ? authorizationSource.permissionsFor(account.subject())
            : tokenIdentity.permissions();
        return TokenValidationResult.valid(new Identity(
            account.subject(),
            account.username(),
            account.email() != null ? account.email() : tokenIdentity.email(),
            profiles,
            permissions,
            Map.of()
        ));
    }

    private ExternalTokenConstraints constraints() {
        String issuer = requiredConfig("issuer");
        String jwksUri = configOr("jwks-uri", issuer + "/protocol/openid-connect/certs");
        return new ExternalTokenConstraints(issuer, jwksUri, configOr("audience", ""));
    }

    private Map<String, String> baseClientForm() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", requiredConfig("client-id"));
        String secret = configOr("client-secret", "");
        if (!secret.isBlank()) {
            form.put("client_secret", secret);
        }
        return form;
    }

    private JsonNode postToken(final Map<String, String> form) {
        String tokenUri = configOr("token-uri", requiredConfig("issuer") + "/protocol/openid-connect/token");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                ? OBJECT_MAPPER.createObjectNode()
                : OBJECT_MAPPER.readTree(response.body());
            if (response.statusCode() >= 400) {
                String description = text(body, "error_description");
                throw new UnauthorizedException(
                    description == null || description.isBlank() ? "Invalid credentials" : description
                );
            }
            return body;
        } catch (UnauthorizedException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UnauthorizedException("Failed to contact identity provider");
        }
    }

    private HostContext host() {
        if (getContext() instanceof HostContext host) {
            return host;
        }
        throw new IllegalStateException("Host context is required for keycloak-idp");
    }

    private static <T> T optionalCapability(final HostContext host, final String adapterId, final Class<T> type) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        Object adapter = host.findClientAdapter(adapterId);
        if (adapter == null) {
            return null;
        }
        if (!type.isInstance(adapter)) {
            throw new IllegalStateException(
                "Adapter '" + adapterId + "' does not implement " + type.getName()
            );
        }
        return type.cast(adapter);
    }

    private static String encodeForm(final Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return body.toString();
    }

    private static String text(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
