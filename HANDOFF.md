# Sadak: project handoff

Read this first when picking the project up on a fresh machine. `README.md` covers how the app works, `DESIGN.md` the visual system, and `final_plan.md` the original scope.

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
| Customers | Public tracking page only, no accounts |
| Project type | Portfolio project, no deadline |

## Open items

1. **Visual views: not built.** The user finds Trips, Shipments and other tabs too list-like. The design is in `docs/mockups/views/boards.html`: trips timetable, shipments horizon, incidents board, fleet yard, each with a List toggle. Awaiting a decision on which to build; Trips was recommended first.
2. **Background colour: undecided.** Someone suggested trying other page backgrounds. Options shown: olive (current), paper white, survey blue, sand, forest tint, stone grey, contour clay, dark ink.
3. **Bug: on screens narrower than about 1000 px, the control tower map collapses to a thin strip.** Not fixed.
4. **The driver app header** shows the name "Sadak" but not the ਸੜਕ logo.

## Setup on a fresh machine

Tools live in `D:\Dev`, installed from portable zips, with no admin rights or PATH changes. `.mvn/maven.config` and `scripts/env.ps1` expect these paths.

1. Clone to `D:\Projects\Fleet`.
2. Unzip Temurin JDK 21 to `D:\Dev\jdk-21`, and the PostgreSQL 17 Windows binaries to `D:\Dev\pgsql` (the pgAdmin folder can be deleted).
3. Create the database cluster in `D:\Dev\pgsql\data` with `initdb -U postgres -A scram-sha-256 -E UTF8 --locale=C --pwfile=<file>`. In `postgresql.conf`, set `listen_addresses = 'localhost'` and `port = 5433`.
4. Create `.env.local` in the project root (it is gitignored, so generate new passwords):

   ```
   FLEET_DB_HOST=localhost
   FLEET_DB_PORT=5433
   FLEET_DB_NAME=fleet
   FLEET_DB_USER=fleet
   FLEET_DB_PASSWORD=<new>
   PG_SUPERUSER=postgres
   PG_SUPERUSER_PASSWORD=<new>
   FLEET_DEMO_PASSWORD=<new>
   ```

   Then run `scripts/start-db.ps1` and create the role `fleet` plus the databases `fleet` and `fleet_test`, both owned by that role.
5. If local tool folders are restored into the project, list them in `.git/info/exclude` (not `.gitignore`) so they never get committed.
6. Run `scripts/run.ps1`. The app is at <http://localhost:8080>; the first start seeds the demo. Tests: `.\mvnw.cmd test` after `. .\scripts\env.ps1`.

Port 5432 was avoided because another project's Docker database used it. Port 8080 must be free.

## Useful to know

- Static files and templates are served from `target/classes`. While the app runs, `.\mvnw.cmd -q resources:resources` applies CSS and template changes without a restart.
- Thymeleaf gives `th:replace` priority over `th:if` on the same element, so wrap the fragment in a `th:block`.
- Views render after the transaction closes (`open-in-view` is off), so initialise lazy collections in the controller.
- The CSP blocks inline scripts and event handlers. Use `data-` attributes handled in `common.js`.
- OpenStreetMap tiles are used because the CARTO tiles now need an API key.
