package com.fleetops.notification;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.tracking.FleetBroadcaster;
import com.fleetops.trip.RiskBand;

/** In-app notifications: one row per recipient, pushed live to their personal queue. */
@Service
public class NotificationService {

    public static final String QUEUE = "/queue/notifications";

    private final NotificationRepository notifications;
    private final UserAccountRepository users;
    private final FleetBroadcaster broadcaster;
    private final OperationalClock clock;

    public NotificationService(NotificationRepository notifications, UserAccountRepository users,
                               FleetBroadcaster broadcaster, OperationalClock clock) {
        this.notifications = notifications;
        this.users = users;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    @Transactional
    public void notifyRoles(List<Role> roles, RiskBand severity, String title, String body, String link) {
        for (UserAccount user : users.findEnabledWithAnyRole(roles)) {
            Notification n = notifications.save(new Notification(user.getId(), severity, title, body, link, clock.now()));
            Map<String, Object> payload = Map.of("id", n.getId(), "severity", severity.name(), "title", title,
                "body", body == null ? "" : body, "link", link == null ? "" : link);
            String username = user.getUsername();
            afterCommit(() -> broadcaster.publishToUser(username, QUEUE, payload));
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(String username) {
        return notifications.findTop30ByUserIdOrderByCreatedAtDesc(userId(username));
    }

    @Transactional(readOnly = true)
    public long unreadCount(String username) {
        return notifications.countByUserIdAndReadAtIsNull(userId(username));
    }

    @Transactional
    public void markAllRead(String username) {
        notifications.findByUserIdAndReadAtIsNull(userId(username)).forEach(n -> n.markRead(clock.now()));
    }

    private Long userId(String username) {
        return users.findByUsername(username).orElseThrow(() -> new NotFoundException("User", username)).getId();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
