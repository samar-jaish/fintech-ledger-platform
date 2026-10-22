package com.portfolio.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;

/**
 * Keeps the open live streams, by user, and pushes new notifications to the users they concern.
 * It lives in this process only. With several instances of the service, each Kafka message reaches just one of them, so a
 * real multi-instance setup needs a shared channel between instances (see ADR 0020).
 */
@Component
class NotificationBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(NotificationBroadcaster.class);
    static final String EVENT_NAME = "notification";

    private final ConcurrentHashMap<String, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final long timeoutMs;

    NotificationBroadcaster(@Value("${notification.stream.timeout-ms:300000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** Opens a stream for one user. The stream is removed from the registry however it ends (closed, timed out, failed). */
    SseEmitter subscribe(String subject) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Set<SseEmitter> mine = streams.computeIfAbsent(subject, s -> new CopyOnWriteArraySet<>());
        mine.add(emitter);
        Runnable remove = () -> {
            mine.remove(emitter);
            streams.computeIfPresent(subject, (s, set) -> set.isEmpty() ? null : set);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(emitter::complete);   // a normal end: the client reconnects
        emitter.onError(e -> remove.run());
        // Send something at once. The response headers reach the browser only with the first bytes, so without this the
        // client would not know the stream is open until the first keep-alive, many seconds later.
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Sends a stored notification to every open stream of the two users it concerns. */
    void publish(Notification n) {
        Stream.of(n.fromOwner(), n.toOwner()).filter(o -> o != null).distinct()
                .forEach(owner -> send(owner, n.viewFor(owner)));
    }

    /** Sends one event on a single stream (used to replay what a reconnecting client missed). Returns false if it failed. */
    boolean sendTo(SseEmitter emitter, NotificationView view) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(view.id())).name(EVENT_NAME)
                        .data(view, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
                return false;
            }
        }
    }

    private void send(String owner, NotificationView view) {
        Set<SseEmitter> mine = streams.get(owner);
        if (mine == null) {
            return;
        }
        for (SseEmitter emitter : mine) {
            if (!sendTo(emitter, view)) {
                log.debug("A stream for a user closed while sending; it will reconnect");
            }
        }
    }

    /** A comment line (ignored by clients) keeps idle connections from being closed by proxies, and removes dead streams. */
    @Scheduled(fixedDelayString = "${notification.stream.heartbeat-ms:20000}")
    void heartbeat() {
        streams.values().forEach(set -> set.forEach(emitter -> {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException e) {
                    emitter.completeWithError(e);
                }
            }
        }));
    }

    int openStreams() {
        return streams.values().stream().mapToInt(Set::size).sum();
    }
}
