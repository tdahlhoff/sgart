package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.LiveConnectionRegistry;
import de.sgart.collaboration.application.exception.LiveConnectionClosedException;
import de.sgart.collaboration.application.query.AuthorizeHouseholdStream;
import de.sgart.collaboration.application.query.AuthorizeHouseholdStream.Authorization;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The per-household live-sync SSE stream (Story 4.4, T1, AC1/AC3): {@code GET
 * /households/{id}/stream} — a Spring MVC {@link SseEmitter} (the stack is {@code
 * spring-boot-starter-web}, not WebFlux). Authorization runs through the same Identity ACL every
 * command/query uses ({@link AuthorizeHouseholdStream}), <strong>synchronously, before the emitter
 * is returned</strong>: once the response is committed at {@code 200} and the emitter takes over,
 * a deferred {@code NotAMemberException} could no longer produce a {@code 403} — {@code
 * WriteErrorAdvice}'s existing {@code handleNotAMember} mapping only works because the check
 * happens here, at the top of the method, before any {@link SseEmitter} exists.
 *
 * <p>Owns the heartbeat schedule (a periodic SSE comment, shorter than any proxy idle timeout) and
 * the emitter's own timeout (0 — Servlet-spec "no timeout" — since a persistent stream must survive
 * past the MVC async default of ~30s). Registers/deregisters through {@link LiveConnectionRegistry}
 * so this controller never depends on the fan-out's {@code adapter.out} package (hexagonal
 * direction) — both sides depend only on the {@code application} port.
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}")
class HouseholdStreamController {

    private final AuthorizeHouseholdStream authorizeHouseholdStream;
    private final LiveConnectionRegistry liveConnectionRegistry;
    private final ScheduledExecutorService heartbeatScheduler;
    private final long heartbeatIntervalSeconds;
    private final long emitterTimeoutMillis;

    HouseholdStreamController(
            AuthorizeHouseholdStream authorizeHouseholdStream,
            LiveConnectionRegistry liveConnectionRegistry,
            @Value("${sgart.live-sync.heartbeat-interval-seconds:15}") long heartbeatIntervalSeconds,
            @Value("${sgart.live-sync.emitter-timeout-ms:0}") long emitterTimeoutMillis) {
        this.authorizeHouseholdStream = authorizeHouseholdStream;
        this.liveConnectionRegistry = liveConnectionRegistry;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.emitterTimeoutMillis = emitterTimeoutMillis;
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "household-stream-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt, @PathVariable String householdId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);
        // Fail fast, synchronously, before any SseEmitter exists — see the class comment.
        Authorization authorization = authorizeHouseholdStream.authorize(caller.keycloakUserId(), householdId);

        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
        SseHouseholdConnection connection = new SseHouseholdConnection(emitter, authorization.householdId());
        liveConnectionRegistry.register(authorization.householdId(), authorization.memberId(), connection);

        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeatOrClose(connection),
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            heartbeat.cancel(false);
            liveConnectionRegistry.deregister(authorization.householdId(), authorization.memberId(), connection);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(throwable -> cleanup.run());

        return emitter;
    }

    /** A failed heartbeat send means the peer is gone — close the emitter, which triggers cleanup. */
    private void sendHeartbeatOrClose(SseHouseholdConnection connection) {
        try {
            connection.sendHeartbeat();
        } catch (LiveConnectionClosedException deadConnection) {
            connection.close();
        }
    }
}
