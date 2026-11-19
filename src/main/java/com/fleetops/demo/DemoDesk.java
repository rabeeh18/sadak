package com.fleetops.demo;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.time.StartupGate;
import com.fleetops.complaint.Complaint;
import com.fleetops.complaint.ComplaintCategory;
import com.fleetops.complaint.ComplaintRepository;
import com.fleetops.complaint.CustomerHelpService;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;

/**
 * Demo mode: the warehouse desk side. Gives every warehouse a made-up desk phone, adds the demo
 * Warehouse user (Patiala), and now and then has a customer leave a note or report a problem, so
 * the desk and the control tower have something to show. Runs on every start, so databases seeded
 * before these features existed get them too.
 */
@Component
@Profile("demo")
public class DemoDesk {

    public static final String DEMO_WAREHOUSE_USERNAME = "warehouse";

    private static final Logger log = LoggerFactory.getLogger(DemoDesk.class);

    private static final Map<String, String> PHONES = Map.of(
        "LDH", "0161 240 1100", "JUC", "0181 222 4300", "ASR", "0183 256 7700",
        "PTA", "0175 230 1200", "BTI", "0164 221 5500", "MOH", "0172 466 0900");

    private static final List<String> NOTES = List.of(
        "Shop shuts 1–2 pm for lunch. Please come before or after.",
        "Leave it with the chemist next door if we're closed.",
        "Call 10 minutes before. The gate is on the back lane.",
        "Use the side entrance, the front is being repaired.",
        "Ask for Rajinder at the counter.",
        "Heavy item, please bring it to the godown at the back.",
        "Deliver after 4 pm please.");

    private record Problem(ComplaintCategory category, String message) {
    }

    private static final List<Problem> PROBLEMS = List.of(
        new Problem(ComplaintCategory.LATE, "Was promised by the afternoon, we need it for a function this evening."),
        new Problem(ComplaintCategory.LATE, "The time keeps moving later. Will it come today?"),
        new Problem(ComplaintCategory.WRONG_ADDRESS, "The address on the label is our old shop. Please bring it to the new one on Railway Road."),
        new Problem(ComplaintCategory.OTHER, "Can the driver collect a return parcel when he comes?"),
        new Problem(ComplaintCategory.DRIVER, "Driver called and was rude when I asked him to wait two minutes."));

    private final UserAccountRepository users;
    private final WarehouseRepository warehouses;
    private final ShipmentRepository shipments;
    private final ComplaintRepository complaints;
    private final CustomerHelpService help;
    private final OperationalClock clock;
    private final StartupGate gate;
    private final TransactionTemplate tx;
    private final Random random = new Random();

    public DemoDesk(UserAccountRepository users, WarehouseRepository warehouses, ShipmentRepository shipments,
                    ComplaintRepository complaints, CustomerHelpService help, OperationalClock clock, StartupGate gate,
                    PlatformTransactionManager txManager) {
        this.users = users;
        this.warehouses = warehouses;
        this.shipments = shipments;
        this.complaints = complaints;
        this.help = help;
        this.clock = clock;
        this.gate = gate;
        this.tx = new TransactionTemplate(txManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(6)
    public void setUp() {
        tx.executeWithoutResult(s -> {
            for (Warehouse w : warehouses.findAll()) {
                if (w.getDeskPhone() == null && PHONES.containsKey(w.getCode())) {
                    boolean hub = w.getCode().equals("MOH") || w.getCode().equals("LDH");
                    w.updateDesk(PHONES.get(w.getCode()), LocalTime.of(hub ? 8 : 9, 0), LocalTime.of(hub ? 20 : 19, 0));
                }
            }
            Warehouse patiala = warehouses.findAll().stream().filter(w -> w.getCode().equals("PTA")).findFirst().orElse(null);
            if (patiala != null && users.findByUsername(DEMO_WAREHOUSE_USERNAME).isEmpty()) {
                // Same password as the other demo accounts.
                users.findByUsername("dispatcher").ifPresent(d -> {
                    UserAccount u = users.save(new UserAccount(DEMO_WAREHOUSE_USERNAME, DEMO_WAREHOUSE_USERNAME + "@sadak.example",
                        d.getPasswordHash(), "Harleen Kaur", EnumSet.of(Role.WAREHOUSE)));
                    u.setWarehouseId(patiala.getId());
                    log.info("Demo warehouse desk user added for {}", patiala.getName());
                });
            }
        });
        if (complaints.count() == 0) {
            // A first complaint and a couple of notes so the desk isn't empty on the first visit.
            customerActivity(true);
            customerActivity(false);
            customerActivity(false);
        }
    }

    /** Every minute or so of real time, a customer may write in. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void tick() {
        if (!gate.isOpen() || clock.paused()) {
            return;
        }
        int roll = random.nextInt(10);
        if (roll < 2) {
            customerActivity(true);
        } else if (roll < 5) {
            customerActivity(false);
        }
    }

    private void customerActivity(boolean complaint) {
        try {
            List<Shipment> out = shipments.findByStatus(ShipmentStatus.OUT_FOR_DELIVERY);
            if (out.isEmpty()) {
                return;
            }
            // Patiala gets a share of everything, so the demo desk user always has something.
            List<Shipment> patiala = out.stream().filter(s -> s.getOriginWarehouse().getCode().equals("PTA")).toList();
            List<Shipment> pool = !patiala.isEmpty() && random.nextBoolean() ? patiala : out;
            Shipment s = pool.get(random.nextInt(pool.size()));
            if (complaint) {
                if (complaints.countByStatusNot(Complaint.Status.RESOLVED) >= 4) {
                    return;
                }
                Problem p = PROBLEMS.get(random.nextInt(PROBLEMS.size()));
                String phone = "9" + (100_000_000L + (long) (random.nextDouble() * 899_999_999L));
                help.file(s.getId(), p.category(), p.message(), phone);
            } else if (s.getCustomerNote() == null) {
                help.leaveNote(s.getId(), NOTES.get(random.nextInt(NOTES.size())));
            }
        } catch (RuntimeException e) {
            log.debug("Demo customer activity skipped: {}", e.getMessage());
        }
    }
}
