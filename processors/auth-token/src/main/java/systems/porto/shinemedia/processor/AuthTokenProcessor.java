package systems.porto.shinemedia.processor;

import lombok.Data;
import systems.porto.api.auth.AuthenticationGrant;
import systems.porto.api.auth.AuthenticationRequest;
import systems.porto.api.auth.AuthenticationResult;
import systems.porto.api.auth.UnauthorizedException;
import systems.porto.api.spi.OperationLog;
import systems.porto.api.spi.RequestContext;
import systems.porto.business.BusinessProcessor;
import systems.porto.business.config.Config;
import systems.porto.context.Context;
import systems.porto.api.auth.dto.AuthTokenResponse;
import systems.porto.api.auth.dto.CreateAuthTokenRequest;
import systems.porto.api.auth.dto.UpdateAuthPasswordRequest;

import java.time.Duration;
import java.time.Instant;

@Data
public class AuthTokenProcessor implements BusinessProcessor<Context> {

    private Config config;

    @Override
    public String id() {
        return "auth-token";
    }

    @Override
    public void process(final Context context) {
        if (!(context instanceof RequestContext requestContext)) {
            throw new IllegalStateException("Request context is required");
        }
        String operation = requestContext.getOperation();
        bindActivityUser(requestContext, operation);
        OperationLog.start(operation);
        try {
            requestContext.setResponse(switch (operation) {
                case "auth-token-create" -> issue(requestContext);
                case "auth-password-update" -> changePassword(requestContext);
                default -> throw new IllegalArgumentException("Unknown auth-token operation: " + operation);
            });
            OperationLog.completed(operation);
        } catch (RuntimeException | Error ex) {
            OperationLog.failed(operation, ex);
            throw ex;
        }
    }

    private static void bindActivityUser(final RequestContext ctx, final String operation) {
        if ("auth-token-create".equals(operation)) {
            CreateAuthTokenRequest request = ctx.getRequestBody(CreateAuthTokenRequest.class);
            if (request != null) {
                OperationLog.bindUser(request.username());
            }
            return;
        }
        ctx.principal().ifPresent(principal -> OperationLog.bindUser(principal.username()));
    }

    private AuthTokenResponse issue(final RequestContext ctx) {
        CreateAuthTokenRequest request = ctx.getRequestBody(CreateAuthTokenRequest.class);
        if (request == null) {
            throw new UnauthorizedException("Token request is required");
        }
        AuthenticationResult result = ctx.identityProvider().authenticate(toAuthenticationRequest(request));
        if (result.identity() != null) {
            OperationLog.bindUser(result.identity().username());
        }
        return toResponse(result);
    }

    private AuthTokenResponse changePassword(final RequestContext ctx) {
        UpdateAuthPasswordRequest request = ctx.getRequestBody(UpdateAuthPasswordRequest.class);
        if (request == null) {
            throw new UnauthorizedException("Password change request is required");
        }
        String username = ctx.requirePrincipal().username();
        ctx.identityProvider().changePassword(username, request.currentPassword(), request.newPassword());
        return toResponse(ctx.identityProvider().authenticate(
            AuthenticationRequest.password(username, request.newPassword())
        ));
    }

    private static AuthTokenResponse toResponse(final AuthenticationResult result) {
        long expiresIn = 0L;
        if (result.accessToken() != null && result.accessToken().expiresAt() != null) {
            expiresIn = Math.max(0, Duration.between(Instant.now(), result.accessToken().expiresAt()).toSeconds());
        }
        return new AuthTokenResponse(
            result.accessToken() == null ? null : result.accessToken().value(),
            result.accessToken() == null ? "Bearer" : result.accessToken().tokenType(),
            expiresIn,
            result.refreshToken() == null ? null : result.refreshToken().value(),
            result.identity().subject(),
            result.identity().username(),
            result.identity().profiles(),
            result.identity().permissions(),
            result.identity().passwordExpired()
        );
    }

    private static AuthenticationRequest toAuthenticationRequest(final CreateAuthTokenRequest request) {
        AuthenticationGrant grant = grantOf(request.grantType());
        return switch (grant) {
            case PASSWORD -> AuthenticationRequest.password(request.username(), request.password());
            case REFRESH_TOKEN -> AuthenticationRequest.refresh(request.refreshToken());
            case ACCESS_TOKEN -> AuthenticationRequest.accessToken(request.password());
            case AUTHORIZATION_CODE -> AuthenticationRequest.authorizationCode(
                request.code() != null && !request.code().isBlank() ? request.code() : request.password(),
                request.redirectUri()
            );
        };
    }

    private static AuthenticationGrant grantOf(final String grantType) {
        if (grantType == null || grantType.isBlank() || "password".equalsIgnoreCase(grantType)) {
            return AuthenticationGrant.PASSWORD;
        }
        if ("refresh_token".equalsIgnoreCase(grantType)) {
            return AuthenticationGrant.REFRESH_TOKEN;
        }
        if ("authorization_code".equalsIgnoreCase(grantType)) {
            return AuthenticationGrant.AUTHORIZATION_CODE;
        }
        throw new UnauthorizedException("Unsupported grant_type: " + grantType);
    }
}
