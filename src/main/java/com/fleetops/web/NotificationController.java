package com.fleetops.web;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fleetops.notification.NotificationService;

@RestController
public class NotificationController {

    private final NotificationService notifications;
    private final Fmt fmt;

    public NotificationController(NotificationService notifications, Fmt fmt) {
        this.notifications = notifications;
        this.fmt = fmt;
    }

    @GetMapping("/api/notifications")
    public List<Map<String, Object>> recent(Authentication auth) {
        return notifications.recent(auth.getName()).stream().map(n -> Map.<String, Object>of(
            "id", n.getId(), "severity", n.getSeverity().name(), "title", n.getTitle(),
            "body", n.getBody() == null ? "" : n.getBody(), "link", n.getLink() == null ? "" : n.getLink(),
            "read", n.getReadAt() != null, "ago", fmt.ago(n.getCreatedAt()))).toList();
    }

    @GetMapping("/api/notifications/unread")
    public Map<String, Object> unread(Authentication auth) {
        return Map.of("count", notifications.unreadCount(auth.getName()));
    }

    @PostMapping("/api/notifications/read")
    public Map<String, Object> markRead(Authentication auth) {
        notifications.markAllRead(auth.getName());
        return Map.of("ok", true);
    }
}
