package com.github.dimitryivaniuta.gateway.auth;

import com.github.dimitryivaniuta.gateway.dto.LoginRequest;
import com.github.dimitryivaniuta.gateway.dto.TokenResponse;
import com.github.dimitryivaniuta.gateway.dto.UserRow;
import com.github.dimitryivaniuta.gateway.security.JwtKeyManager;
import com.github.dimitryivaniuta.gateway.security.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;

/**
 * Reactive authentication controller that verifies credentials and issues RS256 JWT access tokens.
 * <p>
 * - Validates a user against the local gateway user store (R2DBC).<br>
 * - Audits login attempts to {@code login_attempts} (partitioned).<br>
 * - Issues access tokens signed with the current {@code kid} from {@link JwtKeyManager}.<br>
 * - No servlet APIs; WebFlux-only.
 */
@Slf4j
@Validated
@RestController
@RequestMapping(path = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AuthController {

    /** Password encoder (BCrypt) used to verify user passwords. */
    private final PasswordEncoder encoder;

    /** Key manager providing the current RS256 private key and JWKS exposure. */
    private final JwtKeyManager jwtKeyManager;

    /** JWT issuing settings: issuer, audience, TTL, rotation/retention (typed via @ConfigurationProperties). */
    private final JwtProperties jwtProperties;

    /** Reactive R2DBC template for user lookup and login audit writes. */
    private final R2dbcEntityTemplate template;

    /**
     * Authenticates the user and returns an OAuth 2.0 style access token response.
     *
     * @param tenantId optional tenant context (header). If absent, token will omit {@code tenant_id}.
     * @param agent user agent header (for audit).
     * @param request reactive server request for remote address extraction.
     * @param body validated login request containing username and password.
     * @return a reactive mono with {@link TokenResponse} or 401 on failure.
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TokenResponse> login(
            @RequestHeader(name = "X-Tenant-ID", required = false) final String tenantId,
            @RequestHeader(name = "User-Agent", required = false) final String agent,
            final org.springframework.http.server.reactive.ServerHttpRequest request,
            @Valid @RequestBody final LoginRequest body) {

        final String username = body.getUsername();
        final String password = body.getPassword();
        final String usernameCi = username == null ? "" : username.toLowerCase();

        // 1) Lookup user case-insensitively (uses CI index you created in Flyway)
        return selectUserByUsernameCi(username)
                .switchIfEmpty(auditAndFail(usernameCi, false, agent, request, "not_found"))
                .flatMap(u -> {
                    // 2) Verify status and password
                    if (u.status() != 0) { // 0 == ACTIVE
                        return auditAndFail(usernameCi, false, agent, request, "user_status_" + u.status());
                    }
                    if (!encoder.matches(password, u.passwordHash())) {
                        return auditAndFail(usernameCi, false, agent, request, "bad_credentials");
                    }

                    // 3) Success → issue JWT and audit success
                    final Instant now = Instant.now();
                    final Duration ttl = Objects.requireNonNullElse(jwtProperties.getAccessTokenTtl(), Duration.ofMinutes(10));
                    final Instant exp = now.plus(ttl);

                    return Mono.defer(() -> generateJwt(u.id(), u.username(), tenantId, now, exp))
                            .flatMap(tok -> auditLogin(usernameCi, true, agent, request)
                                    .thenReturn(new TokenResponse(
                                            tok,
                                            "Bearer",
                                            (int) ttl.getSeconds(),
                                            now.getEpochSecond(),
                                            // scopes: keep minimal; extend to build from roles if needed
                                            "orders.read",
                                            tenantId
                                    )));
                });
    }

    /**
     * Selects a user by a case-insensitive username.
     *
     * @param username the username as provided.
     * @return a mono with the user row or empty if not found.
     */
    private Mono<UserRow> selectUserByUsernameCi(final String username) {
        final String sql = """
        SELECT id, username, password_hash, status
        FROM users
        WHERE lower(username) = lower($1)
        LIMIT 1
        """;
        RowsFetchSpec<UserRow> rows = template.getDatabaseClient()
                .sql(sql)
                .bind("$1", username)
                .map((r, m) -> new UserRow(
                        r.get("id", Long.class),
                        r.get("username", String.class),
                        r.get("password_hash", String.class),
                        // status is SMALLINT in DB
                        nullSafeShort(r.get("status", Short.class))
                ));
        return rows.one();
    }

    /**
     * Inserts a login attempt audit row into the partitioned table.
     *
     * @param usernameCi lowered username.
     * @param success whether the login succeeded.
     * @param agent user agent string (nullable).
     * @param request server request for remote address.
     * @return completion mono.
     */
    private Mono<Void> auditLogin(
            final String usernameCi,
            final boolean success,
            final String agent,
            final org.springframework.http.server.reactive.ServerHttpRequest request) {

        final String sql = """
        INSERT INTO login_attempts(id, created_at, username_ci, success, ip, user_agent)
        VALUES (DEFAULT, now(), $1, $2, $3, $4)
        """;

        final String ip = remoteIp(request);
        return template.getDatabaseClient()
                .sql(sql)
                .bind("$1", usernameCi)
                .bind("$2", success)
                .bind("$3", ip == null ? Parameter.empty(org.springframework.r2dbc.core.ParameterType.INET) : Parameter.from(ip))
                .bind("$4", agent)
                .then();
    }

    /**
     * Audits a failed login attempt and returns a 401 error as a Mono error.
     *
     * @param usernameCi lowered username.
     * @param success always false for this path.
     * @param agent agent header.
     * @param request server request.
     * @param reason short failure reason for logs.
     * @return a mono error with ResponseStatusException(401).
     */
    private Mono<TokenResponse> auditAndFail(
            final String usernameCi,
            final boolean success,
            final String agent,
            final org.springframework.http.server.reactive.ServerHttpRequest request,
            final String reason) {

        log.warn("Login failure for user={} reason={}", usernameCi, reason);
        return auditLogin(usernameCi, success, agent, request)
                .then(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")));
    }

    /**
     * Generates a signed RS256 JWT with kid/iss/aud/nbf/iat/exp and common claims.
     *
     * @param userId subject (sub).
     * @param username preferred username.
     * @param tenantId optional tenant id for multi-tenant scoping.
     * @param iat issued-at time.
     * @param exp expiry time.
     * @return mono with compact serialized JWT string.
     */
    private Mono<String> generateJwt(
            final Long userId,
            final String username,
            final String tenantId,
            final Instant iat,
            final Instant exp) {

        return Mono.fromCallable(() -> {
            final var rsaKey = jwtKeyManager.currentSigningKey();
            final var signer = new RSASSASigner(rsaKey.toPrivateKey());
            final var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(rsaKey.getKeyID())
                    .build();

            // Claims: keep compact; add mt/resource_access if you wish to carry tenant roles at login time
            final Map<String, Object> claims = Map.ofEntries(
                    Map.entry("iss", jwtProperties.getIssuer()),
                    Map.entry("aud", List.of(jwtProperties.getAudience())),
                    Map.entry("sub", String.valueOf(userId)),
                    Map.entry("preferred_username", username),
                    Map.entry("iat", iat.getEpochSecond()),
                    Map.entry("nbf", iat.getEpochSecond()),
                    Map.entry("exp", exp.getEpochSecond()),
                    Map.entry("scope", "orders.read" + (hasWrite(username) ? " orders.write" : "")),
                    Map.entry("tenant_id", tenantId == null ? "" : tenantId)
            );

            final var jwt = new SignedJWT(header, com.nimbusds.jwt.JWTClaimsSet.parse(JSONObjectUtils.toJSONString(claims)));
            jwt.sign(signer);
            return jwt.serialize();
        });
    }

    /**
     * Extracts remote IP address from the request.
     *
     * @param req server request
     * @return textual IP or {@code null} if missing
     */
    private String remoteIp(final org.springframework.http.server.reactive.ServerHttpRequest req) {
        // Prefer X-Forwarded-For if present; otherwise use the connection remote address
        final var xff = req.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",", 2)[0].trim();
        final InetSocketAddress addr = req.getRemoteAddress();
        return addr == null ? null : addr.getAddress().getHostAddress();
    }

    /**
     * Simple heuristic to decide write scope (placeholder). Replace with real role check if needed.
     *
     * @param username username
     * @return true if we grant write scope
     */
    private boolean hasWrite(final String username) {
        // Replace with a real role lookup if you want scopes derived from roles.
        return true;
    }

    /**
     * Converts a {@link Short} possibly-null value to primitive short with default 0.
     *
     * @param s boxed smallint
     * @return value or 0 if null
     */
    private short nullSafeShort(final Short s) {
        return s == null ? 0 : s;
    }

}
