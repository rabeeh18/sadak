# Sadak

*Fleet operations intelligence for Punjab* (sadak: Punjabi for road)

A control tower for a delivery fleet in Punjab, India. It tracks vehicles live on a map, recalculates ETAs continuously, scores delivery risk with transparent rules, opens incidents when something goes wrong, and helps dispatchers fix it, recording every decision.

Java 21 · Spring Boot 4.1 · Thymeleaf · Leaflet · STOMP over WebSocket · PostgreSQL 17 · Flyway

> All vehicles, people, businesses and shipments are synthetic demo data. Town locations and PIN codes are approximate public facts.

---

## Run it

Requirements, installed portably under `D:\Dev` (no admin rights, no Docker):

| Tool | Location |
|---|---|
| JDK 21 (Temurin) | `D:\Dev\jdk-21` |
| PostgreSQL 17 | `D:\Dev\pgsql`, data in `D:\Dev\pgsql\data`, port **5433** |
| Maven | via the Maven Wrapper; libraries in `D:\Dev\m2` |

Credentials live in `.env.local` (gitignored): database user, superuser, and the demo users' password (`FLEET_DEMO_PASSWORD`).

```bash
powershell -ExecutionPolicy Bypass -File scripts/run.ps1
```

This starts PostgreSQL if needed, then the app on <http://localhost:8080>. The first start migrates the schema and seeds the demo (about 15 seconds). On the sign-in page, choose **Dispatcher**, **Admin** or **Driver (phone view)** to look around without a password.

| Script | What it does |
|---|---|
| `scripts/env.ps1` | Points the current PowerShell session at the JDK, PostgreSQL and `.env.local` |
| `scripts/start-db.ps1` / `stop-db.ps1` | Starts / stops the local PostgreSQL |
| `scripts/run.ps1` | Database + app |
| `scripts/reset-db.ps1` | Drops and recreates the database; the next start re-seeds the demo (stop the app first) |
| `scripts/smoke-test.ps1` | Signs in and requests every main page |

Tests (unit, plus integration tests against the `fleet_test` database):

```bash
powershell -Command ". .\scripts\env.ps1; .\mvnw.cmd test"
```

Memory while running: about 320 MB for the app and 85 MB for PostgreSQL.

---

## A five-minute demo

1. **Control tower.** Sign in as Dispatcher. Vehicles move along real roads (routes from OSRM, fetched once per plan and stored). The clock in the header is simulated; use its menu to change speed (1×–120×) or pause.
2. **Pick a trip** in the right-hand index. The map dims everything else and draws the planned route (blue dashed) against the travelled path (black). The panel shows ETAs per stop and **why the trip has its risk score**, rule by rule.
3. **Simulate a disruption** from the same panel: *Slow down*, *Stop +20 min*, *Detour*, *Go silent* or *Breakdown*. Within seconds the score rises, the reasons change and, past the threshold, an incident opens with a notification.
4. **Handle it.** Open the incident and acknowledge it. Then record a call with the driver, adjust the ETA, recalculate the route, or, for a breakdown, take the **recommended replacement vehicle**. The new route first goes to the breakdown site to collect the cargo, then continues. Completed stops are untouched.
5. **Driver view.** Sign in as Driver (or open it on a phone). Gurpreet Singh has a planned trip: start it, confirm arrivals, record deliveries with the recipient's name, or report a failed delivery.
6. **Public tracking.** `/track`: tracking number + delivery PIN code shows status, ETA and the vehicle's approximate square.
7. **Analytics.** Eight weeks of seeded history: on-time rate, delays, utilisation, downtime, incident resolution time, route efficiency, plus *where the plan runs slow*. Moga and Gurdaspur legs are seeded to run late, and the town table finds them. Click a day to see its trips; export CSV.
8. **Audit log** (Admin → Audit log): every reassignment, status change, rule change and incident action, with before/after values.

The demo keeps itself running. During the simulated working day, each warehouse books shipments and dispatches trips as vehicles come free. About one trip in four is promised tightly, so the risk engine has something to find. Broken-down vehicles return from the workshop after a few hours, and quiet nights are skipped to the next morning.

---

## How it works

```
Simulator ──┐                                  ┌─> STOMP /topic/fleet/positions (1/s)
Driver GPS ─┴─> LocationIngestionService ──> LiveFleet (in-memory picture of live trips)
                 · project onto route                │
                 · geofence arrival                  ▼
                 · throttled history   MonitoringService (every 4 s)
                                         · EtaCalculator   · RiskEngine (rules from DB)
                                         · SLA breaches    · incidents (deduplicated)
                                         └─> STOMP /topic/fleet/trips, /topic/fleet/incidents
```

**Modules** (one package each, a modular monolith): `trip` (planning, lifecycle, reassignment), `tracking`, `simulation`, `risk`, `incident`, `fleet`, `shipment`, `routing`, `notification`, `audit`, `settings`, `security`, `web` (controllers, analytics), `demo` (seeder, history, live operations).

**Design rules the code keeps:**

- **One clock.** Every timestamp, ETA and deadline comparison reads `OperationalClock`, which runs at simulation speed and survives restarts.
- **Routes are fetched once.** Planning calls the public OSRM server (throttled to 1 request/s, cached). If it is unreachable, the route is a straight-line estimate and is labelled as such.
- **Trips own the current assignment.** `trip_assignments` keeps the history. Partial unique indexes guarantee one live trip per vehicle and per driver; allocation also takes row locks.
- **Incidents are idempotent.** A partial unique index allows one open incident per (trip, type). Repeat detections update it, and concurrent ones retry. A condition that clears resolves the incident itself if nobody has picked it up yet.
- **Explainable risk.** Each rule adds points when its condition holds (points and thresholds are editable by admins). The total maps to low / medium / high / critical. It is an indicator, not a probability.
- **Atomic operations.** Planning, delivery completion, reassignment and cancellation are single transactions with audit rows written inside them.

### ETA

```
ETA(stop k) = now + time left at the current stop
            + pace × planned drive time from here to stop k
            + planned service time of the stops before k
            + dispatcher adjustment
```

Pace is the ratio of actual to planned driving time over the last stretch, clamped to 0.85–2.5.

### Risk rules (defaults)

| Rule | Points | Condition |
|---|---:|---|
| Projected late | 1.5 / min, max 45 | worst remaining shipment's ETA past its deadline |
| Deadline pressure | 15 | a shipment's ETA within 15 min of its deadline |
| Unexpected stop | 25 | stationary > 8 min away from any stop |
| Location stale | 30 | no fix for > 5 min |
| Off planned route | 20 | > 500 m from the route |
| Long stop | 15 | at a stop > service time + 10 min |
| History of delays | 10 | a shipment already delayed or re-attempted |
| Vehicle breakdown | 60 | reported broken down |

Bands: medium ≥ 25, high ≥ 50, critical ≥ 75. An SLA incident opens from **high**.

### Metric definitions (Analytics)

| Metric | Definition |
|---|---|
| On-time delivery rate | delivered by deadline ÷ (delivered + failed) |
| Average delivery delay | mean lateness of late deliveries only |
| SLA breaches | late deliveries, failed deliveries, and deadlines passed en route |
| Fleet utilisation | hours on trips ÷ (vehicles in service × days × 12 operating hours) |
| Vehicle downtime | breakdown and workshop hours overlapping the range |
| Incident resolution time | mean time from detection to resolution |
| Route efficiency | actual ÷ planned trip duration, shown as ± % |
| Stop completion rate | completed ÷ closed delivery stops |

### Security

Roles: Admin, Dispatcher, Driver (public tracking is anonymous). Enforcement is on the server: URL rules, plus ownership checks so a driver can only touch their own trip. WebSocket subscriptions are authorised per topic, and STOMP CONNECT carries the CSRF token. A Content-Security-Policy allows scripts only from this app and jsDelivr. Public tracking needs the tracking number **and** the delivery PIN code, gives the same answer for either mistake, and is rate-limited.

---

## Interface

The visual language is a **Survey of India topographic sheet**: a tinted sheet margin, a double neatline around the map, lettered quarter-degree grid squares (A–M × 1–12), graticule ticks in the collar, and survey colours (water-blue routes, contour-brown warehouses, road-red alerts). Grid references appear on every trip, vehicle and stop, so "TR-1512 is in I10" means the same square on the map. Risk scores are set like spot heights (▲45). See [DESIGN.md](DESIGN.md).

Map tiles are © OpenStreetMap contributors. Icons are from [Lucide](https://lucide.dev) (ISC licence).

---

## Not included (by design)

Machine learning, microservices, real GPS hardware, route optimisation beyond nearest-neighbour, customer accounts, payments, email. See [final_plan.md](final_plan.md) for what was planned for later.
