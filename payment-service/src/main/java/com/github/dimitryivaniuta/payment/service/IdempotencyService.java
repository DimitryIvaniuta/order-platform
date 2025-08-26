package com.github.dimitryivaniuta.payment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Reactive idempotency service backed by the 'idempotency_request' table.
 *
 * Usage pattern in a controller/service:
 * <pre>
 * return idempotency.execute(
 *     tenantId,
 *     idempotencyKeyFromHeader,
 *     requestDto,                    // used only to compute request fingerprint
 *     200,                           // status to store on success
 *     actionMono,                    // Mono<T> that performs the work
 *     new TypeReference<T>(){}       // type for (de)serialization
 * ).map(CachedResult::body);         // or inspect .fromCache() / .statusCode()
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;

    /** Encapsulates the outcome of execute(): the body, cache flag, and stored status code (if available). */
    @Value
    public static class CachedResult<T> {
        T body;
        boolean fromCache;
        Integer statusCode; // nullable if not present in DB yet
    }

    /** Reused key with a different fingerprint. Map to HTTP 409. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) { super(message); }
    }

    /** Key exists but response is not stored yet (another request in-flight). Map to 409/425/429 as you prefer. */
    public static class IdempotencyInProgressException extends RuntimeException {
        public IdempotencyInProgressException(String message) { super(message); }
    }

    /**
     * Execute an operation with idempotency. If a cached response exists for the same
     * key & fingerprint, return it. Otherwise run {@code action}, persist the response,
     * and return it.
     *
     * @param tenantId       Tenant (stored for auditing; uniqueness is on key_hash)
     * @param idempotencyKey Raw header value (hashed before storing)
     * @param requestObject  Any object used to build a stable fingerprint (canonical JSON)
     * @param successStatus  HTTP-like status to store on success (e.g., 200/201)
     * @param action         The work to perform if not cached
     * @param typeRef        Type of the response for (de)serialization
     */
    public <T> Mono<CachedResult<T>> execute(
            String tenantId,
            String idempotencyKey,
            Object requestObject,
            int successStatus,
            Mono<T> action,
            TypeReference<T> typeRef
    ) {
        Objects.requireNonNull(action, "action");
        final String t = requireNonBlank(tenantId, "tenantId");
        final String key = requireNonBlank(idempotencyKey, "idempotencyKey");

        final String keyHash = sha256Hex(key);
        final String fingerprint = sha256Hex(canonicalize(requestObject));

        // Try to insert the key first (writer wins). Others will hit conflict path.
        return insertPlaceholder(t, keyHash, fingerprint)
                .flatMap(inserted -> {
                    if (inserted) {
                        // We are the winner → run the action and store the response.
                        return action.flatMap(result ->
                                serialize(result, typeRef)
                                        .flatMap(bytes -> updateResponse(keyHash, fingerprint, successStatus, bytes)
                                                .thenReturn(new CachedResult<>(result, false, successStatus))
                                        )
                        ).onErrorResume(err -> {
                            // Keep placeholder row; optionally store a failure status if you want.
                            log.warn("Idempotent action failed; keyHash={}", keyHash, err);
                            return Mono.error(err);
                        });
                    } else {
                        // Existing entry → check fingerprint and either replay or raise conflict.
                        return fetchExisting(keyHash).flatMap(existing -> {
                            if (existing == null) {
                                // Very unlikely: race where row vanished; treat as new writer
                                return execute(tenantId, idempotencyKey, requestObject, successStatus, action, typeRef);
                            }
                            if (!fingerprint.equals(existing.requestFingerprint())) {
                                return Mono.error(new IdempotencyConflictException(
                                        "Idempotency-Key reused with a different request fingerprint"));
                            }
                            if (existing.responseBytes() == null || existing.statusCode() == null) {
                                return Mono.error(new IdempotencyInProgressException(
                                        "Idempotent request is in progress; try again"));
                            }
                            return deserialize(existing.responseBytes(), typeRef)
                                    .map(body -> new CachedResult<>(body, true, existing.statusCode()));
                        });
                    }
                });
    }

    /* ======================================================================
       SQL ops
       ====================================================================== */

    private Mono<Boolean> insertPlaceholder(String tenantId, String keyHash, String fingerprint) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return db.sql("""
                INSERT INTO idempotency_request (tenant_id, key_hash, request_fingerprint, created_at, updated_at)
                VALUES (:t, :kh, :fp, :now, :now)
                ON CONFLICT (key_hash) DO NOTHING
                """)
                .bind("t", tenantId)
                .bind("kh", keyHash)
                .bind("fp", fingerprint)
                .bind("now", now)
                .fetch().rowsUpdated()
                .map(rows -> rows != null && rows > 0);
    }

    private Mono<Long> updateResponse(String keyHash, String fingerprint, int status, byte[] bytes) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return db.sql("""
                UPDATE idempotency_request
                   SET response_bytes = :rb, status_code = :sc, updated_at = :now
                 WHERE key_hash = :kh AND request_fingerprint = :fp
                """)
                .bind("rb", bytes)
                .bind("sc", status)
                .bind("now", now)
                .bind("kh", keyHash)
                .bind("fp", fingerprint)
                .fetch().rowsUpdated()
                .map(n -> n == null ? 0 : n);
    }

    private record Existing(String requestFingerprint, Integer statusCode, byte[] responseBytes) {}
    private Mono<Existing> fetchExisting(String keyHash) {
        return db.sql("""
                SELECT request_fingerprint, status_code, response_bytes
                FROM idempotency_request
                WHERE key_hash = :kh
                LIMIT 1
                """)
                .bind("kh", keyHash)
                .map((row, meta) -> new Existing(
                        row.get("request_fingerprint", String.class),
                        row.get("status_code", Integer.class),
                        row.get("response_bytes", byte[].class)))
                .one()
                .onErrorResume(e -> Mono.empty());
    }

    /* ======================================================================
       JSON (de)serialization
       ====================================================================== */

    private <T> Mono<byte[]> serialize(T body, TypeReference<T> type) {
        try {
            // Serialize with ObjectMapper for storage. You can customize views if needed.
            return Mono.just(objectMapper.writeValueAsBytes(body));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private <T> Mono<T> deserialize(byte[] bytes, TypeReference<T> type) {
        if (bytes == null) return Mono.empty();
        try {
            return Mono.just(objectMapper.readValue(bytes, type));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    /* ======================================================================
       Fingerprinting & hashing
       ====================================================================== */

    /** Canonicalize an object to stable JSON for fingerprinting (null-safe). */
    private String canonicalize(Object o) {
        try {
            if (o == null) return "null";
            if (o instanceof CharSequence cs) return cs.toString();
            // Convert to tree then write to normalize ordering/format
            var tree = objectMapper.valueToTree(o);
            return objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            // As a last resort, fall back to toString()
            return String.valueOf(o);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return v;
    }
}
