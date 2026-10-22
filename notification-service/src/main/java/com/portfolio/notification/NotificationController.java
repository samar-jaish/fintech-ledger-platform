package com.portfolio.notification;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * What the signed-in user is told about. Everything is filtered by the token's subject, so a user only ever sees
 * notifications for their own accounts; there is no way to ask for someone else's.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    static final int MAX_LIST = 100;
    static final int MAX_REPLAY = 100;

    private final NotificationRepository notifications;
    private final NotificationBroadcaster broadcaster;

    NotificationController(NotificationRepository notifications, NotificationBroadcaster broadcaster) {
        this.notifications = notifications;
        this.broadcaster = broadcaster;
    }

    /** The newest notifications, newest first. */
    @GetMapping
    List<NotificationView> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIST);
        }
        String subject = jwt.getSubject();
        return notifications.latestFor(subject, limit).stream().map(n -> n.viewFor(subject)).toList();
    }

    /**
     * A live stream (server-sent events). New notifications arrive as they are stored. A client that reconnects sends the id of
     * the last event it saw (Last-Event-ID) and first receives whatever it missed, then the live ones. The same event can
     * arrive twice around a reconnect; clients ignore ids they already have.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt,
                      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        String subject = jwt.getSubject();
        SseEmitter emitter = broadcaster.subscribe(subject);   // register first, so nothing falls between replay and live
        Long after = parse(lastEventId);
        if (after != null) {
            for (Notification missed : notifications.after(subject, after, MAX_REPLAY)) {
                if (!broadcaster.sendTo(emitter, missed.viewFor(subject))) {
                    break;
                }
            }
        }
        return emitter;
    }

    private static Long parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;   // an unreadable id is treated as "no history": live events only
        }
    }
}
