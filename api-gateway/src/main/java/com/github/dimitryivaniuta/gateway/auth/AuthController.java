package com.github.dimitryivaniuta.gateway.auth;

import com.github.dimitryivaniuta.gateway.dto.MintedToken;
import com.github.dimitryivaniuta.gateway.dto.UserWithRoles;
import com.github.dimitryivaniuta.gateway.model.*;
import com.github.dimitryivaniuta.gateway.security.JwtProperties;
import com.github.dimitryivaniuta.gateway.service.UserRoleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive authentication controller that:
 * <ol>
 *   <li>Accepts username/email + password (and tenant),</li>
 *   <li>Verifies credentials against the R2DBC user store,</li>
 *   <li>Builds per-tenant roles and scopes,</li>
 *   <li>Delegates RS256 token minting to {@link JwtIssuerService},</li>
 *   <li>Returns an OAuth2-like token payload.</li>
 * </ol>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>No servlet APIs; WebFlux only.</li>
 *   <li>No {@code @Value}; typed properties via {@link JwtProperties}.</li>
 *   <li>All private fields are alphabetically ordered to satisfy project checkstyle.</li>
 * </ul>
 */
@Slf4j
@Validated
@RestController
@RequestMapping(path = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AuthController {

    /** JWT issuer/audience/ttl configuration. */
    private final JwtProperties jwtProperties;

    /** Password hashing/verification (BCrypt). */
    private final PasswordEncoder passwordEncoder;

    /** Issues signed RS256 JWTs (delegated signer). */
    private final JwtIssuerService tokenService;

    /** Reactive user repository. */
    private final UserRepository userRepository;

    /** Reactive user-role repository (role lookups for token claims). */
    private final UserRoleRepository userRoleRepository;

    private final RoleRepository roleRepository;
    private final UserRoleService userRoleService;


    /**
     * Login endpoint that returns a signed RS256 access token on success.
     *
     * @param req login request containing usernameOrEmail, password, and tenantId
     * @return OAuth2-style token response
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TokenResponse> login(@Valid @RequestBody final LoginRequest req) {
        final String principal = req.getUsernameOrEmail().trim();

        // 1) Find user by username or email (case-insensitive)
        Mono<UserEntity> userMono = userRepository.findByUsernameIgnoreCase(principal)
                .switchIfEmpty(userRepository.findByEmailIgnoreCase(principal));

        return userMono
                .switchIfEmpty(Mono.error(unauthorized("Invalid credentials")))
                // 2) Check status
                .flatMap(user -> {
                    if (user.getStatus() != UserStatus.ACTIVE) {
                        return Mono.error(unauthorized("Account is not active"));
                    }
                    return Mono.just(user);
                })
                // 3) Verify password on boundedElastic (BCrypt is CPU-bound)
                .flatMap(user ->
                        Mono.fromCallable(() -> passwordEncoder.matches(req.getPassword(), user.getPasswordHash()))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(matches -> matches
                                        ? Mono.just(user)
                                        : Mono.error(unauthorized("Invalid credentials"))))
                // 4) Load role names for the user
                .flatMap(user ->
                        userRoleRepository.roleNamesForUser(user.getId()).collectList()
                                .map(roles -> new UserWithRoles(user, roles)))
                // 5) Build scopes + mt map
                .flatMap(uwr -> {
                    List<String> scopes = deriveScopes(uwr.roles());
                    Map<String, List<String>> mt = Map.of(
                            req.getTenantId(), List.copyOf(uwr.roles())
                    );

                    // 6) Delegate to token service
                    return tokenService.mintAccessToken(
                                    String.valueOf(uwr.user().getId()),              // sub
                                    req.getTenantId(),                                // tenant
                                    mt,                                               // multi-tenant role map
                                    scopes,                                           // scopes
                                    uwr.user().getUsername(),                         // preferred_username
                                    uwr.user().getEmail()                              // email
                            )
                            .map(token -> toResponse(token, scopes));
                })
                .doOnSuccess(tr -> log.info("User authenticated user={} tenant={}", principal, req.getTenantId()));
    }

    // ---------- helpers ----------

    private RuntimeException unauthorized(final String msg) {
        // Use a lightweight runtime exception; your global error handler maps it to 401.
        return new IllegalArgumentException(msg);
    }

    /**
     * Simple role->scope mapping example.
     * Map ORDER_READ -> orders.read, ORDER_WRITE -> orders.write; ADMIN -> admin.
     */
    private List<String> deriveScopes(final List<String> roles) {
        List<String> out = new ArrayList<>();
        for (String r : roles) {
            String role = r == null ? "" : r.trim().toUpperCase(Locale.ROOT);
            if (role.isEmpty()) continue;
            switch (role) {
                case "ORDER_READ"  -> out.add("orders.read");
                case "ORDER_WRITE" -> out.add("orders.write");
                case "ADMIN"       -> out.add("admin");
                default            -> { /* ignore unknown roles for scopes; still present in mt */ }
            }
        }
        return out.isEmpty() ? List.of("orders.read") : out;
    }

    private TokenResponse toResponse(final MintedToken token, final List<String> scopes) {
        Duration ttl = token.expiresIn();
        long seconds = ttl != null ? ttl.toSeconds() : jwtProperties.getAccessTokenTtl().toSeconds();
        String scopeStr = String.join(" ", scopes);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("scope", scopeStr);
        return new TokenResponse(token.accessToken(), "Bearer", seconds, extras);
    }

    /**
     * Login request payload.
     */
    @Data
    public static final class LoginRequest {
        /** Username or email (CI lookup). */
        @NotBlank
        private String usernameOrEmail;
        /** Password in clear; verified with BCrypt. */
        @NotBlank
        private String password;
        /** Target tenant id (required for mt claim). */
        @NotBlank
        private String tenantId;
    }

    /**
     * OAuth2-style token response.
     */
    public record TokenResponse(
            /** The signed RS256 JWT. */
            String access_token,
            /** Always "Bearer". */
            String token_type,
            /** Expiration (seconds). */
            long expires_in,
            /** Additional convenience properties (e.g., scope). */
            Map<String, Object> ext
    ) { }

}
