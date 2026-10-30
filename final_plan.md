# Fleet Operations Intelligence Platform — Final Plan

This document refines `implementation_plan.md` (the original vision, kept unchanged) into the plan we will build from. Where the two disagree, this one wins.

**Status:** Built (phases P0–P8) · 2026-10-21. See the README for how to run it.

**Changes made during the build:**

- **Spring Boot 4.1** (the current line) instead of 3.x.
- **Custom CSS** ("Toposheet", see DESIGN.md) instead of Bootstrap, to meet the "not generic" brief.
- **OpenStreetMap tiles**: the planned CARTO tiles now require an API key.
- **Demo operations generator**: books and dispatches trips through the simulated day, and skips quiet nights, so the demo never runs dry.
**Project type:** Portfolio project, no fixed deadline
**Demo region:** Punjab, India

---

## 1. Decisions taken

| Topic | Decision |
|---|---|
| Purpose | Unchanged: live monitoring → rule-based risk detection → incident handling, in one control tower |
| Architecture | Modular monolith, Java 21 + Spring Boot (current release line, confirmed at project creation) |
| Frontend | Thymeleaf + Bootstrap 5 + plain JavaScript; Leaflet for maps; Chart.js for charts. No Node.js |
| Real-time | Spring WebSocket/STOMP, with per-topic authorization |
| Database | PostgreSQL 17 (portable install, `D:\Dev\pgsql`, port 5433), Flyway migrations |
| Routing | Public OSRM server, called **once per route plan** and cached in the database; straight-line + average-speed fallback |
| Footprint | No Docker, no self-hosted OSRM, no Testcontainers. Target under ~1 GB RAM total while running |
| Customers | Public tracking page only (tracking number + delivery PIN code). No customer accounts in v1 |
| Notifications | In-app only (dispatcher alerts over WebSocket). Email deferred |
| Audit | Application-level audit log written by services. Database triggers deferred |
| Machine learning | None. Rule-based, explainable scoring only |
| Time zone | Asia/Kolkata for display; UTC in the database |

## 2. Environment

| Item | Location |
|---|---|
| JDK 21 | `D:\Dev\jdk-21` |
| PostgreSQL 17.11 | `D:\Dev\pgsql`, data in `D:\Dev\pgsql\data`, port **5433** |
| Maven | Maven Wrapper in the project; local repository at `D:\Dev\m2` |
| Databases | `fleet` (app) and `fleet_test` (integration tests), owned by role `fleet` |
| Credentials | `.env.local` (gitignored) |
| Session setup | `. .\scripts\env.ps1`, then `scripts\start-db.ps1` |

---

## 3. Modules

Each module has a Level 1 scope (the first complete version) and items that are explicitly later.

### M1 — Control tower (main screen)
- **L1:** Live map of Punjab with vehicles, warehouses, planned routes, actual trail of the selected vehicle, stops (completed/next/upcoming), incident markers. KPI strip calculated from the database. Active-trip table (progress, ETA deviation, risk level, condition). Vehicle side panel (driver, shipments, next stop, ETA, alerts, risk explanation). Live alert feed.
- **Later:** Journey replay, map filters by warehouse.

### M2 — Tracking & simulation
- **L1:** Simulation clock with speed control (1×–60×), pause/resume. Simulator moves vehicles along their cached route geometry. Scenario controls per trip: slow down, extend stop, breakdown, detour, go silent (stop sending locations). Location ingestion pipeline (same API a real GPS device would call). Automatic stop arrival when within ~100 m, departure after service time.
- **Later:** Saved scenario scripts, a real driver-phone GPS feed.

### M3 — Dispatch planning & routes
- **L1:** Build a trip from unassigned shipments at a warehouse. Capacity check (weight vs vehicle capacity). Stop ordering by simple nearest-neighbour. Assign vehicle + driver with eligibility checks (available, licence valid, not on another trip). Fetch and cache route geometry and durations. Route versions: a change keeps completed stops and records the reason and dispatcher.
- **Later:** Dispatcher drag-to-reorder stops, comparing alternative routes, better optimisation.

### M4 — ETA & risk engine
- **L1:** Dynamic ETA per remaining stop (remaining drive time + service times + current delay). SLA comparison per shipment against its deadline. Weighted rule-based risk score with a written explanation ("+30 projected 25 min late, +20 location stale 6 min"). Low / medium / high / critical bands. Rule weights and thresholds stored in the database and editable on an admin page. Scheduled monitoring job plus re-evaluation on each location update.
- **Later:** Historical route averages feeding the ETA (added once the seeder provides history).

### M5 — Incidents & corrective action
- **L1:** Incident types: SLA at risk, breakdown, long stop, route deviation, location stale, failed delivery, vehicle/driver unavailable. Lifecycle: detected → acknowledged → action in progress → resolved. At most one open incident per (type, trip), enforced by the database; repeat detections update it. Actions: record driver contact, adjust ETA, mark delivery failed with reason, escalate, **reassign remaining stops** to a replacement vehicle (transactional, with locking). Replacement-vehicle recommender: filter by available / capacity / licence, rank by distance, show why.
- **Later:** Configurable escalation rules, automatic prioritisation.

### M6 — Master data: fleet, drivers, warehouses, shipments
- **L1:** CRUD for warehouses, vehicles (capacity, status, home warehouse), drivers (linked user account, licence number and expiry), shipments (tracking number, origin warehouse, destination and PIN code, weight, deadline, recipient). Status history for shipments and trips. Light maintenance records (start/end, reason) that make a vehicle unavailable and feed downtime analytics.
- **Later:** Bulk CSV import of shipments.

### M7 — Driver app (mobile-friendly pages)
- **L1:** Driver sees today's trip, route map and stop list; confirms arrival/departure (overrides the automatic arrival); completes delivery with proof (recipient name + note); reports breakdown or failed delivery. Server enforces "driver can only touch their own trip".
- **Later:** Photo proof, using the phone's GPS as the location source.

### M8 — Public shipment tracking
- **L1:** Page where anyone enters a tracking number + destination PIN code and sees status, current ETA and a coarse map. Rate-limited. No customer accounts.
- **Later:** Customer accounts, shipment requests, delay emails.

### M9 — Notifications
- **L1:** In-app notifications for dispatchers (new/escalated incidents), stored with read/unread status.
- **Later:** Email via a local test mail server.

### M10 — Analytics & reporting
- **L1:** Date / warehouse / vehicle / driver filters. On-time delivery rate, average delay, SLA breach count, fleet utilisation, vehicle downtime, incident resolution time, route efficiency (planned vs actual), stop completion rate. Daily trend charts, CSV export, drill-down to trips and incidents. Metric definitions documented on the page.
- **Later:** Monthly summaries, scheduled reports, route performance comparisons.

### M11 — Security & audit (cross-cutting)
- **L1:** Roles: Admin, Dispatcher, Driver (customer = anonymous tracking only). Form login with sessions, CSRF on. Method-level and ownership checks. WebSocket subscription authorization. Audit log (actor, time, entity, action, before/after) for reassignment, status changes, incident actions, route changes, vehicle availability, settings changes. Admin audit viewer.
- **Later:** Database-side audit triggers.

### M12 — Demo data seeder (tooling)
- **L1:** Punjab reference data (warehouses in Ludhiana, Amritsar, Jalandhar, Patiala, Bathinda, Mohali; ~20 vehicles; ~20 drivers; delivery points in surrounding towns). Generator for ~8 weeks of completed historical trips with realistic delays, incidents and failures so analytics and averages work from day one. Demo users per role. Runs only in a `demo` profile.

---

## 4. Key design rules

1. **One clock.** Every service reads time from an `OperationalClock` bean, never `Instant.now()` directly. In simulation it runs faster than real time; deadlines, ETAs and timestamps all stay consistent.
2. **Routes are fetched once.** Planning calls OSRM, stores geometry, distance and per-leg durations as a route version. The simulator and ETA engine only read the stored route. If OSRM is unavailable, plan with straight lines at a configured average speed and mark the route as estimated.
3. **Location writes are throttled.** The simulator ticks every second and the map gets every tick over WebSocket, but location history is saved only every N seconds of simulated time (configurable) and pruned after a retention period.
4. **The trip owns the current assignment.** `trips.vehicle_id` / `trips.driver_id` are the source of truth; `trip_assignments` is history only.
5. **Explainable risk.** Every score is stored with the list of rules that contributed. The UI always shows why.
6. **Incidents are idempotent.** A partial unique index allows only one open incident per (type, trip). Detection upserts.
7. **Atomic operations.** Reassignment, delivery completion and incident resolution are single transactions. Vehicle/driver allocation uses row locks so two dispatchers can't take the same vehicle.
8. **Validated state machines.** Trips, stops, shipments and incidents can only move through allowed transitions, enforced in services and backed by DB check constraints.
9. **Module boundaries.** One Java package per module; modules talk through services or application events, not each other's repositories.

---

## 5. Data model (revised)

| Table | Purpose |
|---|---|
| `users`, `roles`, `user_roles` | Authentication and roles |
| `warehouses` | Name, address, coordinates |
| `vehicles` | Registration, type, capacity (kg), status, home warehouse, last known position |
| `drivers` | Linked user, licence number and expiry, status |
| `maintenance_records` | Vehicle unavailability periods and reasons |
| `shipments` | Tracking number, origin warehouse, destination, PIN code, weight, deadline, recipient, status, current stop |
| `trips` | Warehouse, current vehicle and driver, status, planned/actual start and end, latest risk score |
| `trip_assignments` | History of vehicle/driver allocations with reason and actor |
| `trip_stops` | Ordered stops: location, planned arrival, current ETA, service time, actual arrival/departure, status |
| `route_versions` | Geometry, distance, per-leg durations, estimated flag, reason, created by, active flag |
| `vehicle_locations` | Throttled position history (vehicle, trip, time, lat/lng, speed) |
| `status_history` | Lifecycle transitions for trips, stops and shipments |
| `risk_evaluations` | Score, band and contributing rules per evaluation |
| `risk_rules` / `system_settings` | Configurable weights, thresholds, simulation and retention settings |
| `incidents` | Type, severity, trip/shipment, status, owner, timestamps, resolution |
| `incident_actions` | Actions taken against an incident |
| `delivery_proofs` | Recipient name, note, time, position |
| `sla_breach_events` | Recorded deadline breaches |
| `notifications` | In-app notifications with read status |
| `audit_log` | Actor, time, entity, action, before/after JSON |

Changes from the original plan: shipments now link to stops; `routes` + `route_stops` became `trip_stops` + `route_versions`; `assignments` is history only; added `maintenance_records`, `risk_evaluations` and `risk_rules`/`system_settings`.

---

## 6. Build phases

Each phase ends with something runnable and shown to you before the next one starts.

| Phase | Delivers | Modules |
|---|---|---|
| **P0 Skeleton** | Spring Boot project, Maven Wrapper, Flyway, login with three roles, base layout, health page | M11 (base) |
| **P1 Master data** | Warehouses, vehicles, drivers, shipments CRUD; Punjab reference seed | M6, M12 (reference) |
| **P2 Planning & routing** | Build trips from shipments, assign resources, fetch/cache routes, planned route on a map | M3 |
| **P3 Live tracking** | Simulation clock, simulator, ingestion, WebSocket, control tower map v1, automatic stop progress | M2, M1 (map) |
| **P4 ETA & risk** | Dynamic ETAs, SLA checks, risk scoring with explanations, rules admin page, monitoring job | M4, M1 (trip table) |
| **P5 Incidents** | Detection, lifecycle, actions, recommender, reassignment of remaining stops, in-app notifications | M5, M9 |
| **P6 Driver & public pages** | Driver app with proof of delivery, public tracking page | M7, M8 |
| **P7 History & analytics** | Historical seeder, analytics dashboard, CSV export, drill-downs | M12 (history), M10 |
| **P8 Hardening** | Audit viewer, permission tests, integration tests for core workflows, README, demo script | M11 |
| **Level 2 (after P8)** | Journey replay, dispatcher route editing, historical averages in ETA, escalation rules, email, photo proof, Docker Compose file for others to run it | — |

**Testing throughout:** JUnit 5 + Mockito for rules and services; integration tests against the `fleet_test` database for transactions, locking, incident deduplication and permissions.

---

## 7. Demo scenario (Punjab)

1. Start the simulation at 30×. Five trips leave the Ludhiana and Jalandhar warehouses towards Moga, Phagwara, Khanna, Kapurthala and Hoshiarpur.
2. Open the control tower and watch the vehicles move along real roads.
3. Select a trip and inspect its shipments, stops, ETAs and risk explanation.
4. Slow one vehicle down; watch two Moga deliveries turn high risk and an incident appear.
5. Acknowledge the incident and record contact with the driver.
6. Break down a vehicle near Phagwara. The recommender suggests an eligible vehicle from Jalandhar.
7. Reassign the remaining stops; the new route and vehicle appear live.
8. Complete deliveries from the driver app with proof.
9. Look up one shipment on the public tracking page.
10. Open analytics (with 8 weeks of seeded history) to see on-time rate, delays, incidents and utilisation.
11. Open the audit log to see every action from the demo.

---

## 8. Out of scope

Machine learning, microservices, real GPS hardware, complex route optimisation, customer accounts, payments/invoicing, multi-company tenancy, native mobile apps.
