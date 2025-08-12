package com.github.dimitryivaniuta.gateway.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Reactive, in-memory event bus for streaming per-saga status updates to clients (e.g., via SSE).
 * <p>
 * Design highlights:
 * <ul>
 *   <li><b>Replay-latest</b>: late subscribers immediately receive the most recent status.</li>
 *   <li><b>Backpressure-friendly</b>: Reactor Sinks with multicast semantics.</li>
 *   <li><b>Memory safety</b>: idle saga streams are evicted after a configurable idle window.</li>
 *   <li><b>Thread-safe</b>: all structures are concurrent and emissions use tryEmit with safe handling.</li>
 * </ul>
 * For cross-process fan-out at very large scale, replace this with Redis pub/sub or a compacted Kafka
 * "saga-state" topic; the API surface of this component remains the same.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaEventBus {

    /** Default idle time before a saga stream is evicted from memory. */
    private static final Duration DEFAULT_IDLE_EVICTION = Duration.ofMinutes(15);

    /** Map of saga id to sink holder (thread-safe, high concurrency). */
    private final Map<UUID, SinkHolder> sinks = new ConcurrentHashMap<>();

    /**
     * Publishes a new status update for the given saga. If no subscribers are present, the
     * last value is retained (replay-latest) and will be delivered to future subscribers.
     *
     * @param status the latest {@link SagaStatusEntity} to emit (must include non-null id)
     */
    public void publish(final SagaStatusEntity status) {
        if (status == null || status.getId() == null) {
            log.warn("Ignoring publish of null status or null saga id");
            return;
        }
        final UUID id = status.getId();
        final SinkHolder holder = sinks.computeIfAbsent(id, k -> SinkHolder.create());
        holder.lastAccessEpochMillis = System.currentTimeMillis();

        final Sinks.EmitResult res = holder.sink.tryEmitNext(status);
        if (!res.isSuccess()) {
            // If sink is terminated, recreate and re-emit once to ensure continuity.
            if (res == Sinks.EmitResult.FAIL_TERMINATED) {
                log.debug("Recreating terminated sink for sagaId={}", id);
                sinks.compute(id, (k, old) -> {
                    SinkHolder fresh = SinkHolder.create();
                    fresh.lastAccessEpochMillis = System.currentTimeMillis();
                    return fresh;
                });
                final Sinks.EmitResult retry = sinks.get(id).sink.tryEmitNext(status);
                if (!retry.isSuccess()) {
                    log.warn("Failed to emit saga status after sink recreation sagaId={} result={}", id, retry);
                }
            } else {
                log.debug("Non-success emit result sagaId={} result={}", id, res);
            }
        }

        // Opportunistic cleanup on the write path.
        pruneIdle(DEFAULT_IDLE_EVICTION);
    }

    /**
     * Returns a hot {@link Flux} of status updates for the given saga. The stream:
     * <ul>
     *   <li>Replays the latest known value immediately on subscription.</li>
     *   <li>Continues with live updates as they arrive.</li>
     *   <li>Decrements subscriber count on termination to enable idle eviction.</li>
     * </ul>
     *
     * @param sagaId unique identifier of the saga to observe
     * @return hot flux of {@link SagaStatusEntity} updates
     */
    public Flux<SagaStatusEntity> stream(final UUID sagaId) {
        final SinkHolder holder = sinks.computeIfAbsent(sagaId, k -> SinkHolder.create());
        holder.lastAccessEpochMillis = System.currentTimeMillis();

        return holder.sink
                .asFlux()
                .doOnSubscribe(s -> {
                    holder.subscribers.incrementAndGet();
                    holder.lastAccessEpochMillis = System.currentTimeMillis();
                    log.trace("Saga stream subscribed sagaId={} subscribers={}", sagaId, holder.subscribers.get());
                })
                .doFinally(signalType -> {
                    holder.subscribers.decrementAndGet();
                    holder.lastAccessEpochMillis = System.currentTimeMillis();
                    log.trace("Saga stream terminated sagaId={} subscribers={} reason={}",
                            sagaId, holder.subscribers.get(), signalType);
                    // Opportunistic cleanup when streams close.
                    pruneIdle(DEFAULT_IDLE_EVICTION);
                });
    }

    /**
     * Completes the saga stream (no more updates). The sink is marked completed and will be
     * removed by idle eviction shortly after.
     *
     * @param sagaId id to complete
     * @return true if completion was acknowledged by the sink
     */
    public boolean complete(final UUID sagaId) {
        final SinkHolder holder = sinks.get(sagaId);
        if (holder == null) {
            return false;
        }
        holder.lastAccessEpochMillis = System.currentTimeMillis();
        final Sinks.EmitResult res = holder.sink.tryEmitComplete();
        return res.isSuccess();
    }

    /**
     * Periodic eviction of idle saga streams to prevent memory buildup.
     * Runs on a fixed delay; adjust the schedule if needed in your application config.
     */
    @Scheduled(fixedDelayString = "PT5M")
    public void scheduledEviction() {
        pruneIdle(DEFAULT_IDLE_EVICTION);
    }

    /**
     * Removes streams that have no subscribers and have been idle longer than the threshold.
     *
     * @param idleFor duration of idleness required for eviction
     */
    private void pruneIdle(final Duration idleFor) {
        final long cutoff = System.currentTimeMillis() - idleFor.toMillis();
        sinks.entrySet().removeIf(entry -> {
            final SinkHolder h = entry.getValue();
            final boolean evictable = h.subscribers.get() <= 0 && h.lastAccessEpochMillis < cutoff;
            if (evictable) {
                log.debug("Evicting idle saga stream sagaId={} lastAccess={}Z",
                        entry.getKey(), Instant.ofEpochMilli(h.lastAccessEpochMillis));
            }
            return evictable;
        });
    }

    /**
     * Holder for a per-saga sink and lightweight metrics.
     */
    private static final class SinkHolder {
        /** Last time (epoch millis) this holder was accessed or updated. */
        private volatile long lastAccessEpochMillis;

        /** Replay-latest multicast sink; retains last value for late subscribers. */
        private final Sinks.Many<SagaStatusEntity> sink;

        /** Current number of subscribers to this sink. */
        private final AtomicInteger subscribers;

        private SinkHolder(final Sinks.Many<SagaStatusEntity> sink) {
            this.sink = sink;
            this.subscribers = new AtomicInteger(0);
            this.lastAccessEpochMillis = System.currentTimeMillis();
        }

        /** Factory method creating a replay-latest multicast sink holder. */
        private static SinkHolder create() {
            return new SinkHolder(Sinks.many().replay().latest());
        }
    }
}
