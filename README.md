# Sadak

*Fleet operations intelligence for Punjab* (sadak: Punjabi for road)

A control tower for a delivery fleet in Punjab, India. It tracks vehicles live on a map, recalculates ETAs continuously, gives every trip a light (On track, Watch or Needs action) from transparent rules, opens an incident for every problem that needs a person, and offers one obvious fix for each, recording every decision.

Java 21 · Spring Boot 4.1 · Thymeleaf · Leaflet · STOMP over WebSocket · PostgreSQL 17 · Flyway

> All vehicles, people, businesses and shipments are synthetic demo data. Town locations and PIN codes are approximate public facts.

---

## Run it

Requirements (standard installs, no Docker):

| Tool | How |
|---|---|
| JDK 21 | `winget install Microsoft.OpenJDK.21` (any JDK 21 with `JAVA_HOME` set works) |
| PostgreSQL 17 | `winget install PostgreSQL.PostgreSQL.17`: runs as a Windows service on port **5432** |
| Maven | nothing to install: the Maven Wrapper downloads it |

Credentials live in `.env.local` (gitignored): database user, superuser, and the demo users' password (`FLEET_DEMO_PASSWORD`).

```bash
powershell -ExecutionPolicy Bypass -File scripts/run.ps1
```

This checks that the PostgreSQL service is running, then starts the app on <http://localhost:8080>. The first start migrates the schema and seeds the demo (about 15 seconds). Staff sign in with their email and land on the screen for their role. In demo mode the sign-in page opens on **Try the demo**: one click signs you in as Dispatcher, Warehouse, Driver or Admin. The other tab is **Track a parcel**; email sign-in is a link under the roles. In production the tabs are **Track a parcel** and **Staff sign in**. Admins can start the demo over from Admin → Problems & settings → **Reset demo data**. Demo accounts are `admin@`, `dispatcher@`, `dispatcher2@`, `warehouse@` (the Patiala desk) and `driver@sadak.example`, with the password `FLEET_DEMO_PASSWORD`.

### Demo mode and production mode

| | Demo (default) | Production (`scripts/run.ps1 -Production`, or `SPRING_PROFILES_ACTIVE=prod`) |
|---|---|---|
| Data | Synthetic Punjab company with 8 weeks of history; trips dispatch themselves | Starts empty; the admin adds warehouses, vehicles and drivers |
| Clock | Simulated, 1×–120×, can pause | Real time |
| Vehicle positions | Simulator | Drivers' phones ("share location" in the driver app; needs HTTPS) |
| Sign-in | Email, plus one-click demo sign-in | Email only |

Production needs its own database (`FLEET_DB_NAME`) and these settings, in `.env.local` or the server's environment:

| Setting | Purpose |
|---|---|
| `FLEET_ADMIN_EMAIL`, `FLEET_ADMIN_PASSWORD` | Create the first admin on the very first start. Remove the password afterwards |
| `FLEET_BASE_URL` | Public address used in invite and reset links, e.g. `https://sadak.example.in` |
| `FLEET_SECURE_COOKIES` | `true` by default in production (HTTPS only); `run.ps1 -Production` sets `false` for local http |

| Script | What it does |
|---|---|
| `scripts/env.ps1` | Puts the JDK and `psql` on the current PowerShell session's PATH and loads `.env.local` |
| `scripts/run.ps1` | Starts the app |
| `scripts/reset-db.ps1` | Drops and recreates the database; the next start re-seeds the demo (stop the app first) |
| `scripts/smoke-test.ps1` | Signs in and requests every main page |
| `deploy/*.sh` | Server setup on EC2 (Ubuntu): install, update, reset. See [DEPLOY.md](DEPLOY.md) |

Tests (unit, plus integration tests against the `fleet_test` database):

```bash
powershell -Command ". .\scripts\env.ps1; .\mvnw.cmd test"
```

Memory while running: about 320 MB for the app and 85 MB for PostgreSQL.

---

## A five-minute demo

1. **Control tower.** Open **Try the demo** on the sign-in page and pick Dispatcher. Vehicles move along real roads (routes from OSRM, fetched once per plan and stored). The clock in the header is simulated; use its menu to change speed (1×–120×) or pause.
2. **Pick a trip** in the right-hand index. The map dims everything else and draws the planned route (blue dashed) against the travelled path (black). The panel shows ETAs per stop and **what's wrong**, in plain sentences, each with the points it adds.
3. **Simulate a problem** from the same panel: *Slow down*, *Stuck 20 min*, *Detour*, *GPS lost* or *Breakdown*. Within seconds the trip turns red, "What's wrong" explains why, and an incident opens with a notification.
4. **Fix it**, right in the panel or on the Incidents board. Each incident has one main button: *Give customer a new time* (Will be late), *Call the driver* (Off route, GPS lost, Truck stuck) or *Send replacement truck* (Breakdown, using the recommended vehicle). "I'm on it" marks it as being handled; other actions are under "More". The new route first goes to the breakdown site to collect the cargo, then continues. Completed stops are untouched.
5. **Driver view.** Pick Driver on the demo page (or open it on a phone). Gurpreet Singh has a planned trip: start it, confirm arrivals, record deliveries with the recipient's name, or report a failed delivery.
6. **Public tracking.** `/track`: tracking number + delivery PIN code shows status against the promised time, progress, and a map of the road still to go (with a gap around the truck, whose position is only approximate). The customer can leave the driver a note, call the warehouse desk, or report a problem and follow its progress.
7. **Warehouse desk.** Pick Warehouse on the demo page: the Patiala desk sees only Patiala: today's departures, parcels waiting and coming back, customer notes, and complaints to call back, answer and resolve (the reply shows on the customer's tracking page). Demo customers write in now and then.
8. **Analytics.** Eight weeks of seeded history: on-time rate, delays, utilisation, downtime, incident resolution time, route efficiency, plus *where the plan runs slow*. Moga and Gurdaspur legs are seeded to run late, and the town table finds them. Click a day to see its trips; export CSV.
9. **Audit log** (Admin → Audit log): every reassignment, status change, rule change and incident action, with before/after values.

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
- **Explainable problems.** Every rule is either a heads-up (Watch) or a problem that needs action; the trip's light is the most serious one that holds. Each rule also adds points, shown as the explanation, and admins can tune points and thresholds. The points explain; they do not decide the colour.
- **Atomic operations.** Planning, delivery completion, reassignment and cancellation are single transactions with audit rows written inside them.

### ETA

```
ETA(stop k) = now + time left at the current stop
            + pace × planned drive time from here to stop k
            + planned service time of the stops before k
            + dispatcher adjustment
```

Pace is the ratio of actual to planned driving time over the last stretch, clamped to 0.85–2.5.

### Problems (defaults)

| Problem | Light | Incident | Points | When |
|---|---|---|---:|---|
| Will be late | Needs action | Will be late | 1.5 / min, max 45 | a parcel arrives 5+ min after its promised time |
| Cutting it close | Watch | | 15 | a parcel arrives within 15 min of its promised time, or less than 5 min after |
| Truck stuck on the road | Needs action | Truck stuck | 25 | not moving for > 8 min, away from any stop |
| Truck stuck at a stop | Needs action | Truck stuck | 15 | at a stop > planned time + 10 min |
| GPS lost | Needs action | GPS lost | 30 | no position for > 5 min |
| Off route | Needs action | Off route | 20 | > 500 m from the planned road |
| Late before | Watch | | 10 | a parcel on board was late or re-attempted once |
| Breakdown | Needs action | Breakdown | 60 | the driver reported it |

An incident goes **Open → Being handled → Closed**. The detected problems (all but Breakdown) close themselves, as "Fixed itself", if they go away before anyone picks them up. A failed delivery is not an incident: the parcel's warehouse desk rebooks it.

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

Roles: Admin, Dispatcher, Warehouse, Driver (public tracking is anonymous). A Warehouse account belongs to one warehouse and sees only its parcels, trips and complaints; anything else answers "not found".

**Accounts are invite-only.** There is no public sign-up. The first admin is created once from `FLEET_ADMIN_EMAIL` / `FLEET_ADMIN_PASSWORD`; after that, an admin invites each person with a name, email and role (a Driver account is linked to its driver record, a Warehouse account to its warehouse). The person gets a one-time link, valid 48 hours, and chooses their own password, so admins never know anyone's password. Only admins change roles or disable accounts, every change is audited, and the last active admin cannot be disabled or demoted. Someone who forgets their password asks an admin, who creates a reset link (valid 2 hours) on the Users page. Sadak sends no email: admins pass links on themselves, for example on WhatsApp. Links are random 256-bit tokens stored only as SHA-256 hashes, they work once, and a newer link retires older ones. Their expiry uses real time, never the simulated clock.

Enforcement is on the server: URL rules, plus ownership checks so a driver can only touch their own trip. WebSocket subscriptions are authorised per topic, and STOMP CONNECT carries the CSRF token. A Content-Security-Policy allows scripts only from this app and jsDelivr. Public tracking needs the tracking number **and** the delivery PIN code, gives the same answer for either mistake, and is rate-limited; so are the customer's note and complaint, which need the same two values. The tracking page shows the customer's callback number masked, and never the driver's number.

---

## Interface

The visual language is a **Survey of India topographic sheet**: a tinted sheet margin, a double neatline around the map, lettered quarter-degree grid squares (A–M × 1–12), graticule ticks in the collar, and survey colours (water-blue routes, contour-brown warehouses, road-red alerts). Grid references appear on every trip, vehicle and stop, so "TR-1512 is in I10" means the same square on the map. Each trip carries a light: green On track, yellow Watch, red Needs action. See [DESIGN.md](DESIGN.md).

Map tiles are © OpenStreetMap contributors. Icons are from [Lucide](https://lucide.dev) (ISC licence).

---

## Not included (by design)

Machine learning, microservices, real GPS hardware, route optimisation beyond nearest-neighbour, customer accounts, payments, email, a chatbot.
