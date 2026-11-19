# Sadak: project handoff

Read this first when picking the project up on a fresh machine. `README.md` covers how the app works, `DESIGN.md` the visual system, and `DEPLOY.md` how to put it on a server.

## Where things stand

- All planned phases are built and working: control tower, tracking and simulation, planning, ETA and risk, incidents, fleet, driver app, public tracking, analytics, admin and audit.
- Code packages are still named `com.fleetops`; only the product name changed to Sadak.
- Demo data seeds itself on first start, and the demo keeps dispatching trips by itself.

## Working preferences

- **Talk before building.** Discuss an approach and get a go-ahead first. When asked for "thoughts" or "just the design", don't change the app.
- **Be efficient.** Keep answers short and changes small.
- **Keep it light.** Low RAM and disk use; no Docker dependency, no self-hosted routing. Prefer dropping a heavy feature to adding infrastructure.
- **Commits and repo.** The author is the owner only: no co-author lines and no tool attribution in commit messages, PRs, code or docs. Tool folders stay out of the repo (see setup step 5). Commit or push only when asked.

## Decisions already made

| Topic | Decision |
|---|---|
| Name | Sadak (ਸੜਕ, Punjabi for road) |
| Logo | P4: the ਸੜਕ wordmark in Anek Gurmukhi, with ੜ in road red, beside SADAK in the header |
| Visual style | Toposheet, a Survey of India map sheet (see `DESIGN.md`). A minimal redesign was mocked up and declined for now; the user will raise it if that changes |
| Stack | Java 21, Spring Boot 4.1, Thymeleaf, custom CSS, Leaflet, STOMP, PostgreSQL 17 |
| Region | Punjab, India |
| Customers | Public tracking page only, no accounts. The sign-in card has two tabs, Track a parcel (default) and Staff sign in; the demo role picker is its own page, `/demo`. The tracking page has no staff sign-in link |
| Customer help | On the tracking page: a note for the driver (shown at the stop), the parcel's warehouse desk phone and hours, and Report a problem. Complaints go to that warehouse's desk first and notify dispatchers; the desk's reply shows on the tracking page. No chatbot |
| Roles | Admin, Dispatcher (whole fleet, control room), Warehouse (one warehouse: its desk page, shipments, booking, complaints), Driver |
| Problems and incidents | Each trip has a light: On track, Watch (heads-up), Needs action. Every Needs-action problem is an incident: Will be late, Off route, GPS lost, Truck stuck, Breakdown. Incidents go Open → Being handled → Closed (or "Fixed itself"); each has one main fix. Points remain only as the explanation. Failed deliveries are not incidents (the warehouse desk rebooks them) |
| Staff accounts | Sign in by email. Invite-only: admin invites with a role, the person sets their own password from a one-time link. Sadak sends no email: admins pass invite and reset links on themselves; there is no self-service password reset. First admin from `FLEET_ADMIN_*` |
| Modes | Demo stays (needed for showing the project) and is the default; production mode (`-Production` / `prod` profile) is for deployment |
| Project type | College project (Java subject) and portfolio; meant to be deployed |

## Open items

1. **Bug: on screens narrower than about 1000 px, the control tower map collapses to a thin strip.** Not fixed.
2. **The driver app header** shows the name "Sadak" but not the ਸੜਕ logo.
3. **Travelled path cuts corners.** The black line joins positions saved once per simulated minute (`tracking.persist-every-s`) with straight lines, so it chords across curves and stops short of the truck. Proposed: save a point when the heading turns, not only on a timer, and end the line at the live position. Awaiting a go-ahead.
4. **Deployment: scripts ready, not yet done.** The plan is one EC2 instance (Ubuntu 24.04) in demo mode, so the professor can see it. `deploy/setup-ec2.sh` installs Java, PostgreSQL and Caddy (automatic HTTPS, free `<ip>.sslip.io` name if there is no domain) and runs Sadak as a service; `deploy/update.sh` and `deploy/reset-demo.sh` for later. Steps in `DEPLOY.md`. No public-demo lock (clock or admin): only the professor will visit.

## Setup on a fresh machine

Standard installs, no fixed folders: clone the repo anywhere.

1. Install JDK 21: `winget install Microsoft.OpenJDK.21` (sets `JAVA_HOME`).
2. Create `.env.local` in the project root (it is gitignored, so generate new passwords):

   ```
   FLEET_DB_HOST=localhost
   FLEET_DB_PORT=5432
   FLEET_DB_NAME=fleet
   FLEET_DB_USER=fleet
   FLEET_DB_PASSWORD=<new>
   PG_SUPERUSER=postgres
   PG_SUPERUSER_PASSWORD=<new>
   FLEET_DEMO_PASSWORD=<new>
   ```

3. Install PostgreSQL 17 as a Windows service, using the superuser password from `.env.local`:
   `winget install PostgreSQL.PostgreSQL.17 --override "--mode unattended --unattendedmodeui none --superpassword <PG_SUPERUSER_PASSWORD> --serverport 5432 --disable-components stackbuilder,pgAdmin"`
4. After `. .\scripts\env.ps1`, create the role `fleet` (password `FLEET_DB_PASSWORD`) plus the databases `fleet` and `fleet_test`, both owned by that role, with `psql -U postgres`.
5. Run `scripts/run.ps1`. The app is at <http://localhost:8080>; the first start seeds the demo. Tests: `.\mvnw.cmd test` after `. .\scripts\env.ps1`.

Ports 5432 and 8080 must be free.

## Useful to know

- Static files and templates are served from `target/classes`. While the app runs, `.\mvnw.cmd -q resources:resources` applies CSS and template changes without a restart.
- Thymeleaf gives `th:replace` priority over `th:if` on the same element, so wrap the fragment in a `th:block`.
- Views render after the transaction closes (`open-in-view` is off), so initialise lazy collections in the controller.
- The CSP blocks inline scripts and event handlers. Use `data-` attributes handled in `common.js`.
- OpenStreetMap tiles are used because the CARTO tiles now need an API key.
