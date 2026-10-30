# Fleet Operations Intelligence Platform
## Project Vision & Detailed System Description

**Project type:** Enterprise Java Web Application  
**Core themes:** Real-Time Monitoring · Predictive Analytics · Operational Automation

## Project purpose

A centralized logistics operations platform that monitors a fleet in real time, identifies delivery risks using transparent business rules, and helps dispatchers coordinate corrective action.

### Core pillars

1. **Live fleet monitoring:** Observe vehicles, trips, routes, stops, and delivery progress on a live map.
2. **Predictive delivery intelligence without machine learning:** Calculate updated ETAs, detect potential SLA breaches, identify delays, and prioritize operational risks using deterministic rules and historical data.
3. **Operational response and automation:** Alert dispatchers, track incidents, recommend corrective actions, and manage reassignment and recovery workflows.

The project is primarily an enterprise Java application, not a machine-learning project. Its technical depth comes from backend architecture, transactional business operations, database design, security, event-driven updates, scheduled processing, and integration between modules.

---

## 1. Project overview

The Fleet Operations Intelligence Platform is an enterprise web application that enables a logistics company to monitor its delivery fleet in real time, identify potential delivery failures before they occur, and coordinate corrective actions through a centralized operations dashboard.

The central problem is that logistics companies do not simply need to know where their vehicles are. They need to know whether deliveries are progressing as planned, which operations are at risk, why they are at risk, and what should be done about them.

For example, a dispatcher may have 40 active delivery vehicles. One vehicle is running behind schedule, another has deviated from its planned route, and a third has broken down while carrying several shipments. A conventional tracking system might show their locations, but the dispatcher still has to identify the problems, assess their consequences, and decide what to do.

This platform brings those activities together into one system.

### Three core capabilities

#### Real-time fleet monitoring
Observe vehicles, trips, routes, stops, and delivery progress on a live map.

#### Predictive delivery intelligence
Calculate updated ETAs, detect potential SLA breaches, identify delays, and prioritize operational risks using deterministic rules and historical data.

#### Operational response and automation
Alert dispatchers, track incidents, recommend corrective actions, and manage reassignment and recovery workflows.

### Key distinction

A traditional fleet management application primarily records information. This project uses that information to operate a **continuous monitoring and decision-support system**.

---

## 2. The problem the application solves

Consider a logistics company operating several warehouses and a fleet of delivery vehicles. Each vehicle may carry multiple shipments, visit multiple destinations, and operate under delivery deadlines. Throughout the day, the company receives new orders, vehicles encounter delays, drivers report problems, and delivery schedules change.

Without a centralized operational system, the company faces several problems:

- Dispatchers must manually monitor multiple trips.
- Delivery delays are discovered too late.
- ETAs become inaccurate as trips progress.
- Route deviations and breakdowns may go unnoticed.
- A single disruption can affect multiple shipments.
- Managers struggle to identify recurring operational inefficiencies.
- Historical records may be insufficient to explain why a delivery failed.

The platform addresses these problems by maintaining a continuously updated operational picture of the fleet and applying business rules to identify and manage exceptions.

---

## 3. How the system works end to end

```text
Create shipments and trips
          |
          v
Plan route and assign resources
          |
          v
Start trip
          |
          v
Receive vehicle location updates
          |
          v
Update live map and trip progress
          |
          v
Calculate ETA and operational risk
          |
          v
       Problem?
       /      \
     No        Yes
     |          |
     |       Generate alert
     |          |
     |       Dispatcher reviews
     |          |
     |       Resolve / reassign
     |          |
     +----------+
          |
          v
Delivery completed
          |
          v
Update history and analytics
```

The application revolves around the lifecycle of a delivery operation, from planning through execution, monitoring, exception handling, and final reporting.

### Example scenario

1. A dispatcher assigns a driver and vehicle to a trip containing several delivery stops.
2. The system calculates the planned route and expected arrival times.
3. The driver starts the trip and simulated GPS updates begin flowing into the backend.
4. The vehicle moves across the map while the trip timeline and ETA update.
5. A delay is simulated by slowing the vehicle or extending its stop duration.
6. The system detects that two deliveries are likely to miss their deadlines.
7. An incident is generated and displayed in the dispatcher dashboard.
8. The dispatcher investigates, contacts the driver, or reassigns the remaining deliveries if necessary.
9. The system records the action and tracks whether the incident was resolved.
10. After the trip ends, its duration, delays, and delivery performance contribute to analytics.

These are connected operations: the map, alerts, trip records, and reports all reflect the same underlying application state.

---

## 4. Module 1 — Real-time fleet control tower

This is the main interface and visual centerpiece of the project. When a dispatcher opens the application, they should immediately understand the state of the entire operation.

### Live map

The map displays:

- Vehicle locations, updated continuously.
- Planned routes and intermediate stops.
- Completed and upcoming stops.
- Vehicle identifiers and operational status.
- Selected vehicle's movement trail.
- Active incidents and affected trips.
- Warehouse locations and delivery destinations.

Selecting a vehicle opens its detailed operational view, showing the driver, trip, assigned shipments, next stop, current ETA, and active alerts.

### Operations summary

Display meaningful metrics such as:

- Active trips.
- Deliveries completed today.
- Vehicles currently idle or unavailable.
- Shipments at risk of missing their SLA.
- Active incidents requiring attention.
- Average delivery delay.

These values must be calculated from the database, not hardcoded.

### Active trip panel

Illustrative example:

| Trip | Progress | ETA deviation | Condition |
|---|---:|---:|---|
| TR-104 | 75% | 5 min late | Normal |
| TR-105 | 48% | 32 min late | High risk |
| TR-106 | 61% | Unknown | Location stale |
| TR-107 | 20% | — | Breakdown |

These are illustrative values. The actual application should derive them from live trip data and the risk engine.

The control tower gives the project a clear operational purpose. Instead of navigating through unrelated CRUD pages, the user works from a single screen that connects monitoring, incident management, and corrective action.

---

## 5. Module 2 — Vehicle tracking and trip execution

The application needs a genuine mechanism for updating vehicle positions. Since physical GPS hardware may not be available during the demonstration, implement a **vehicle movement simulator** that follows the same ingestion pipeline intended for real location updates.

### How simulation works

1. A trip starts with a planned road route.
2. The routing service provides route geometry.
3. The simulator advances the vehicle along route coordinates.
4. It sends timestamped location updates to the Spring Boot backend.
5. The backend persists the latest position and publishes an update.
6. The browser moves the vehicle marker without reloading the page.
7. Trip progress, stop status, and ETA are recalculated as appropriate.

The simulator should support multiple concurrent vehicles, adjustable simulation speed, pause/resume, artificial delays, and breakdown scenarios. It should not move markers randomly: each vehicle must follow its assigned route and respond to trip state changes.

### Real-time implementation

- Leaflet for interactive maps.
- A routing service such as OSRM for road geometry and route duration.
- Spring WebSocket/STOMP for server-to-browser updates.
- PostgreSQL for location history and operational records.
- Spring services for processing location updates and evaluating trip state.

The map should distinguish the planned route from the actual traveled path. A future integration with a driver's mobile device or a physical GPS tracker can replace the simulator without changing the core monitoring architecture.

---

## 6. Module 3 — ETA calculation and delivery risk intelligence

This is where the project goes beyond displaying locations. The application should continuously evaluate whether each active delivery is likely to finish within its promised deadline.

**No machine learning is required.** Use mathematical calculations, historical averages, and configurable business rules.

### A. Dynamic ETA

For a trip with multiple remaining stops, calculate the expected arrival using:

- Remaining route duration.
- Expected service time at each upcoming stop.
- Current delay.
- Any additional delay recorded by the driver or dispatcher.

Basic estimate:

```text
ETA = current time + remaining route duration + remaining stop service times
```

Remaining route duration comes from the routing provider or a defined travel-speed model. Remaining stop time comes from planned service duration or an operational estimate. Update the estimate as the vehicle moves, stops are completed, or the route changes.

### B. SLA breach detection

Every shipment can have a promised delivery deadline. The system periodically compares its current ETA against that deadline.

| Example | Expected result |
|---|---|
| Deadline 5:00 PM; ETA 4:45 PM | On schedule |
| Deadline 5:00 PM; ETA 5:20 PM | At risk or likely to breach SLA |

Generate an alert when a configurable threshold is crossed and update its severity as the situation changes.

### C. Operational risk scoring

Build a transparent rule-based risk score using factors such as:

- Minutes of projected delay.
- Time remaining until the deadline.
- Whether a vehicle has stopped unexpectedly.
- Whether its location updates are stale.
- Whether the route has deviated from the plan.
- Whether the shipment has already experienced delays.

Assign weighted points to conditions and classify the score as low, medium, high, or critical. Make weights and thresholds configurable, and explain why each trip received its score.

Do not describe this as a statistically calibrated probability of failure. It is a rule-based risk indicator unless validated against suitable historical data.

### D. Historical operational intelligence

Store completed trip data and calculate:

- Average travel duration by route.
- Average service time at delivery stops.
- On-time delivery rate.
- Delay frequency by warehouse or route.
- Common delivery failure reasons.
- Vehicle downtime.
- Driver and fleet performance metrics.

For example, the application might identify that a particular route consistently takes longer than its planned duration based on recorded trips. This is useful operational intelligence derived from historical data and SQL aggregation, without requiring an ML model.

---

## 7. Module 4 — Incident management and corrective action

A live monitoring system becomes significantly more useful when it manages the problems it detects. An **incident** is a tracked operational problem requiring attention.

### Incident types

- Delivery likely to miss its deadline.
- Vehicle breakdown.
- Unexpectedly long stop.
- Route deviation.
- Missing location updates.
- Failed delivery.
- Driver or vehicle becoming unavailable.

### Incident lifecycle

| State | Meaning |
|---|---|
| Detected | A monitoring rule identifies a problem. |
| Acknowledged | A dispatcher takes ownership. |
| Action in progress | The dispatcher contacts the driver, investigates, or initiates recovery. |
| Resolved | The outcome, resolution time, and actions are recorded. |

Each incident should have a unique identifier, type, severity, affected trip or shipment, detection timestamp, assigned dispatcher, status, notes, and resolution details.

Prevent duplicate incidents from being generated on every monitoring cycle. An existing unresolved incident should be updated when appropriate rather than creating a new incident every few minutes.

### Corrective actions

- Contact a driver and record the response.
- Adjust an expected arrival time.
- Recalculate a route.
- Reassign a vehicle or driver.
- Reassign only the remaining deliveries from a disrupted trip.
- Mark a delivery as failed with a reason.
- Escalate an unresolved incident.

Some actions should require explicit dispatcher approval. For example, notifying a customer of a delay may be automatic, whereas reallocating multiple shipments may require human confirmation.

This module connects predictive alerts to actual operational workflows.

---

## 8. Module 5 — Route management and live trip progress

Routes support the larger fleet-monitoring system rather than defining the entire product. A route consists of an origin, an ordered list of stops, and a destination. A trip represents the actual execution of that route.

### Features

- Create trips with multiple delivery stops.
- Display road-following routes on the map.
- Calculate route distance and estimated duration.
- Track completed, current, and upcoming stops.
- Record actual arrival and departure timestamps.
- Recalculate ETAs when delays occur.
- Show planned versus actual journey history.
- Allow authorized dispatchers to adjust routes.

A simple route-order optimization algorithm can be added if time permits, but it should not displace the control tower, incident management, or live tracking.

A route change must preserve completed deliveries and record the new route version, reason for change, and dispatcher responsible.

---

## 9. Module 6 — Fleet, driver and shipment management

These are necessary enterprise modules, but they should remain supporting capabilities.

### Fleet management

- Vehicle registration and capacity.
- Vehicle availability and maintenance status.
- Driver assignment and license validity.
- Trip history and utilization.
- Breakdown and downtime records.

### Shipment management

- Shipment creation and tracking numbers.
- Origin, destination, weight, and deadline.
- Association with trips and delivery stops.
- Delivery confirmation and failure reasons.
- Status history.

### Driver operations

- View assigned trips.
- View route and upcoming stops.
- Submit location updates.
- Record stop arrival and departure.
- Complete deliveries and submit proof.
- Report breakdowns or failed deliveries.

Operational data from these modules feeds the control tower and risk engine.

---

## 10. Module 7 — Analytics and reporting

Managers need to understand how well the logistics operation is performing, not just its current state. Create an analytics section with filters for date range, warehouse, route, vehicle, and driver.

| Metric | Business meaning |
|---|---|
| On-time delivery rate | Percentage of completed shipments delivered by their deadline. |
| Average delivery delay | Average lateness among delayed or completed shipments, with the definition documented. |
| Fleet utilization | How much of the available fleet is actively being used. |
| Vehicle downtime | Time vehicles are unavailable due to breakdowns or maintenance. |
| SLA breach count | Number of shipments that exceeded their deadlines. |
| Incident resolution time | How quickly dispatchers resolve operational problems. |
| Route efficiency | Difference between planned and actual distance or duration. |
| Stop completion rate | Proportion of planned stops completed successfully. |

Include daily and monthly summaries, trend charts, CSV exports, and drill-downs into the underlying trips or incidents. Analytics should be backed by database queries or reporting views, with consistent metric definitions.

---

## 11. Module 8 — Enterprise security and auditability

Because this is a Java enterprise application, security and data integrity should be first-class requirements.

### Roles

| Role | Responsibilities |
|---|---|
| Admin | Manage users, vehicles, drivers, warehouses, and configuration. |
| Dispatcher | Monitor fleet, manage trips, handle incidents, and reassign resources. |
| Driver | View assigned trips, report progress, and complete deliveries. |
| Customer | Create shipment requests and track their own deliveries. |

Enforce permissions at the backend, including checks that a driver can only update assigned trips and a customer can only access their own shipments.

### Audit log

Record important changes, including:

- Who reassigned a trip.
- Who changed a shipment status.
- Who acknowledged or resolved an incident.
- Which route was replaced and why.
- When a vehicle was marked unavailable.
- Which administrative settings changed.

The audit trail should record the actor, timestamp, affected entity, action, and relevant before/after values.

---

## 12. Technical architecture

The application should be a modular monolith built with Java 21 and Spring Boot.

```text
Browser: Thymeleaf + Bootstrap + Leaflet
                 |
       Spring MVC Controllers
                 |
         Spring Security
                 |
          Java Services
          /     |      \
 Monitoring  Routing   Incident
 & Risk      Provider  Management
          \     |      /
          Spring Data JPA
                 |
             PostgreSQL

Spring WebSocket/STOMP <--> Browser live updates
Scheduled Jobs -------> Monitoring and risk engine
Vehicle Simulator ----> Location ingestion pipeline
```

### Main Java components

- **Controllers:** Handle browser requests and REST endpoints.
- **Services:** Implement business rules, trip operations, incident management, and corrective actions.
- **Repositories:** Access persistent data through JPA/Hibernate.
- **Monitoring service:** Evaluates ETAs, location freshness, route deviations, and SLA risk.
- **Scheduled jobs:** Run periodic checks and generate reports.
- **WebSocket service:** Publishes authorized real-time updates to connected clients.
- **Routing integration:** Retrieves road routes and travel-duration estimates.
- **Simulation service:** Generates realistic location updates for active trips.
- **Security layer:** Enforces role and resource-level access.
- **Database layer:** Maintains operational state, history, audit records, and reporting data.

Use `@Transactional` around operations that must succeed or fail atomically, such as reassignment and delivery completion. Apply appropriate database locking and integrity constraints to prevent conflicting resource allocations.

Do not make database triggers responsible for the entire application workflow. Use them for database-side auditing and integrity effects, while Java services coordinate operational decisions.

### Recommended technology stack

| Component | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.x |
| Web application | Spring MVC, Thymeleaf |
| Frontend styling | Bootstrap 5, custom CSS |
| Interactive map | Leaflet |
| Routing | OSRM or configurable provider |
| Real-time communication | Spring WebSocket/STOMP |
| Database | PostgreSQL |
| ORM | Spring Data JPA, Hibernate |
| Database migrations | Flyway |
| Security | Spring Security |
| Charts | Chart.js |
| Testing | JUnit 5, Mockito, Testcontainers |
| Deployment | Maven, Docker Compose |

---

## 13. Database design

The core schema should support live monitoring, historical analysis, and incident workflows.

| Entity/table | Purpose |
|---|---|
| `users`, `roles`, `user_roles` | Authentication and authorization. |
| `warehouses` | Warehouse locations. |
| `vehicles` | Fleet records and current operational state. |
| `drivers` | Driver profiles and eligibility. |
| `shipments` | Shipment details and delivery deadlines. |
| `routes` | Planned routes and route revisions. |
| `route_stops` | Ordered stops and planned/actual timestamps. |
| `trips` | Actual execution of a route. |
| `trip_shipments` | Shipments carried by a trip. |
| `assignments` | Vehicle and driver allocation history. |
| `vehicle_locations` | Timestamped location history. |
| `status_history` | Shipment and trip lifecycle events. |
| `incidents` | Detected operational problems and resolution. |
| `incident_actions` | Actions taken to resolve incidents. |
| `delivery_proofs` | Delivery confirmation records. |
| `notifications` | Notification status and retry history. |
| `audit_log` | Important application and database changes. |
| `sla_breach_events` | Recorded deadline breaches. |

Use foreign keys, unique constraints, indexes, and validated status transitions. For vehicle location history, retain timestamped records but avoid excessive writes from the simulator. Make update frequency configurable and define a retention policy for older location data.

Historical trip and incident records are especially important because they power the analytics and historical-average calculations.

---

## 14. What makes this an enterprise Java project?

The difficulty should not come from an unnecessarily large feature list. It should come from making several independent application components work together correctly.

- **Layered architecture:** Separation of controllers, services, repositories, and domain models.
- **Dependency injection:** Spring-managed services and integration components.
- **REST API design:** Validated requests, DTOs, pagination, and structured errors.
- **Authentication and authorization:** Session security, roles, and resource-level checks.
- **Transactions:** Atomic reassignment, incident resolution, and delivery completion.
- **Concurrency control:** Preventing conflicting assignments and duplicate incident processing.
- **Scheduled processing:** Continuous SLA monitoring and periodic reporting.
- **Real-time messaging:** WebSocket updates for maps and operational alerts.
- **Database engineering:** Normalization, indexes, constraints, audit triggers, and reporting views.
- **Integration testing:** Verification against real PostgreSQL and simulated operational events.
- **External service integration:** Road routing and notification providers.
- **Observability:** Application logs, error handling, and incident history.

These are substantial enterprise application concerns, even without machine learning.

---

## 15. Recommended scope and implementation priorities

### Level 1 — Essential product

- Central fleet control tower and interactive map.
- Simulated vehicle movement following road routes.
- Trip and stop tracking.
- ETA calculations and SLA risk rules.
- Incident detection and management.
- Spring Security and role-based access.
- PostgreSQL persistence and audit history.
- Tests for core business workflows.

### Level 2 — Enterprise depth

- Dynamic route recalculation.
- Vehicle reassignment after breakdowns.
- Historical journey replay.
- Operational analytics and CSV exports.
- Scheduled notifications and SLA reports.
- Transaction-safe allocation and database triggers.
- Concurrent trip simulation.

### Level 3 — Advanced extensions

- Compare alternative route plans.
- Automatically prioritize incidents.
- Configurable escalation rules.
- Advanced operational scenario simulation.
- Historical route performance comparisons.
- More sophisticated dispatch recommendations.

Do not add machine learning, microservices, complex route optimization, or real GPS hardware unless the core system is already stable.

---

## 16. Final demonstration

The ideal demonstration is a single operational scenario that exercises the entire application.

1. Start several simulated vehicles on different routes.
2. Open the control tower and observe their movement on the map.
3. Select a trip and inspect its shipments, stops, and ETAs.
4. Introduce a delay and observe the risk engine flag an affected delivery.
5. Acknowledge the incident and record the dispatcher's response.
6. Simulate a breakdown on another trip.
7. Reassign the remaining deliveries to an eligible replacement vehicle.
8. Observe the updated route and live vehicle marker.
9. Complete the remaining stops and confirm deliveries.
10. Open analytics to inspect delays, incidents, resolution times, and fleet utilization.
11. Inspect the database audit trail to verify operational changes.

The evaluator should be able to see an event occur, the backend detect it, the application respond, and the resulting state appear consistently across the map, incident records, and analytics.

---

## 17. Final project definition

The Fleet Operations Intelligence Platform is a Java-based enterprise web application that provides centralized, real-time visibility into fleet operations and uses deterministic ETA calculations, historical operational data, and configurable business rules to detect delivery risks, manage incidents, and coordinate corrective action.

Its core value lies in connecting **live monitoring, explainable operational intelligence, and enterprise workflow management** into one cohesive application.

The project is not intended to predict everything or autonomously run an entire logistics company. It demonstrates how a well-designed Java enterprise system can continuously monitor a complex operation, identify exceptions, help humans resolve them, and maintain reliable records of every decision.

---

*End of project description.*
