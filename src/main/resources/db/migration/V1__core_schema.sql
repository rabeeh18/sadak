-- Fleet Operations Intelligence Platform: core schema.
-- All timestamps are stored as timestamptz (UTC); the application reads time from its operational clock.

-- ---------------------------------------------------------------- security
create table users (
    id              bigserial primary key,
    username        varchar(60)  not null unique,
    password_hash   varchar(100) not null,
    full_name       varchar(120) not null,
    enabled         boolean      not null default true,
    created_at      timestamptz  not null default now()
);

create table roles (
    id    smallserial primary key,
    name  varchar(30) not null unique
);

create table user_roles (
    user_id  bigint   not null references users (id) on delete cascade,
    role_id  smallint not null references roles (id),
    primary key (user_id, role_id)
);

insert into roles (name) values ('ADMIN'), ('DISPATCHER'), ('DRIVER');

-- ---------------------------------------------------------------- master data
create table warehouses (
    id          bigserial primary key,
    code        varchar(12)  not null unique,
    name        varchar(120) not null,
    city        varchar(80)  not null,
    address     varchar(255) not null,
    lat         double precision not null,
    lng         double precision not null,
    active      boolean not null default true,
    created_at  timestamptz not null default now()
);

create table vehicles (
    id                 bigserial primary key,
    registration       varchar(20) not null unique,
    type               varchar(30) not null,
    capacity_kg        integer     not null check (capacity_kg > 0),
    home_warehouse_id  bigint      not null references warehouses (id),
    status             varchar(20) not null default 'AVAILABLE'
        check (status in ('AVAILABLE', 'ON_TRIP', 'BROKEN_DOWN', 'MAINTENANCE', 'RETIRED')),
    last_lat           double precision,
    last_lng           double precision,
    last_seen_at       timestamptz,
    simulated          boolean not null default true,
    created_at         timestamptz not null default now()
);

create table drivers (
    id                bigserial primary key,
    user_id           bigint unique references users (id),
    full_name         varchar(120) not null,
    phone             varchar(20)  not null,
    license_number    varchar(30)  not null unique,
    license_expiry    date         not null,
    home_warehouse_id bigint       not null references warehouses (id),
    status            varchar(20)  not null default 'AVAILABLE'
        check (status in ('AVAILABLE', 'ON_TRIP', 'OFF_DUTY', 'UNAVAILABLE')),
    created_at        timestamptz  not null default now()
);

create table maintenance_records (
    id          bigserial primary key,
    vehicle_id  bigint      not null references vehicles (id),
    kind        varchar(20) not null check (kind in ('BREAKDOWN', 'SCHEDULED', 'INSPECTION')),
    reason      varchar(255) not null,
    started_at  timestamptz not null,
    ended_at    timestamptz,
    created_by  varchar(60),
    check (ended_at is null or ended_at >= started_at)
);
create index ix_maintenance_vehicle on maintenance_records (vehicle_id, started_at);
-- At most one open maintenance record per vehicle.
create unique index ux_maintenance_open on maintenance_records (vehicle_id) where ended_at is null;

-- ---------------------------------------------------------------- trips and routes
create table trips (
    id                  bigserial primary key,
    code                varchar(20) not null unique,
    warehouse_id        bigint      not null references warehouses (id),
    vehicle_id          bigint      not null references vehicles (id),
    driver_id           bigint      not null references drivers (id),
    status              varchar(20) not null default 'PLANNED'
        check (status in ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    planned_start       timestamptz not null,
    planned_end         timestamptz,
    actual_start        timestamptz,
    actual_end          timestamptz,
    total_weight_kg     integer     not null default 0,
    delay_adjust_min    integer     not null default 0,
    auto_complete_stops boolean     not null default true,
    risk_score          integer     not null default 0,
    risk_band           varchar(10) not null default 'LOW'
        check (risk_band in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    risk_reasons        text,
    risk_evaluated_at   timestamptz,
    created_by          varchar(60),
    created_at          timestamptz not null default now(),
    version             integer     not null default 0
);
create index ix_trips_status on trips (status);
create index ix_trips_planned_start on trips (planned_start);
-- A vehicle or driver can be on at most one live trip.
create unique index ux_trips_live_vehicle on trips (vehicle_id) where status in ('PLANNED', 'IN_PROGRESS');
create unique index ux_trips_live_driver  on trips (driver_id)  where status in ('PLANNED', 'IN_PROGRESS');

create table route_versions (
    id               bigserial primary key,
    trip_id          bigint      not null references trips (id) on delete cascade,
    version_no       integer     not null,
    active           boolean     not null default true,
    geometry         text        not null,           -- encoded polyline (precision 5)
    distance_m       integer     not null,
    duration_s       integer     not null,
    leg_distances_m  text        not null,           -- comma separated, one per leg
    leg_durations_s  text        not null,
    start_offset_m   integer     not null default 0, -- distance already travelled when this version took over
    estimated        boolean     not null default false, -- true when routing fell back to straight lines
    reason           varchar(255) not null,
    created_by       varchar(60),
    created_at       timestamptz not null default now(),
    unique (trip_id, version_no)
);
create unique index ux_route_active on route_versions (trip_id) where active;

create table trip_stops (
    id                 bigserial primary key,
    trip_id            bigint      not null references trips (id) on delete cascade,
    seq                integer     not null,
    kind               varchar(12) not null default 'DELIVERY' check (kind in ('DELIVERY', 'TRANSFER')),
    name               varchar(160) not null,
    address            varchar(255) not null,
    lat                double precision not null,
    lng                double precision not null,
    service_min        integer     not null default 10,
    planned_arrival    timestamptz,
    eta                timestamptz,
    actual_arrival     timestamptz,
    actual_departure   timestamptz,
    status             varchar(12) not null default 'PENDING'
        check (status in ('PENDING', 'ARRIVED', 'COMPLETED', 'FAILED', 'SKIPPED')),
    route_version_no   integer     not null default 1,
    leg_index          integer     not null default 0,
    unique (trip_id, seq)
);
create index ix_stops_trip on trip_stops (trip_id, seq);

create table trip_assignments (
    id          bigserial primary key,
    trip_id     bigint      not null references trips (id) on delete cascade,
    vehicle_id  bigint      not null references vehicles (id),
    driver_id   bigint      not null references drivers (id),
    reason      varchar(255) not null,
    assigned_by varchar(60),
    assigned_at timestamptz not null
);
create index ix_assignments_trip on trip_assignments (trip_id);

-- ---------------------------------------------------------------- shipments
create table shipments (
    id                 bigserial primary key,
    tracking_no        varchar(20)  not null unique,
    origin_warehouse_id bigint      not null references warehouses (id),
    recipient_name     varchar(120) not null,
    recipient_phone    varchar(20),
    dest_name          varchar(160) not null,
    dest_address       varchar(255) not null,
    dest_pin           varchar(6)   not null,
    dest_lat           double precision not null,
    dest_lng           double precision not null,
    weight_kg          integer      not null check (weight_kg > 0),
    deadline           timestamptz  not null,
    status             varchar(20)  not null default 'CREATED'
        check (status in ('CREATED', 'PLANNED', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED', 'CANCELLED')),
    trip_id            bigint references trips (id),
    stop_id            bigint references trip_stops (id),
    delivered_at       timestamptz,
    failure_reason     varchar(255),
    attempts           integer      not null default 0,
    was_delayed        boolean      not null default false,
    created_at         timestamptz  not null default now()
);
create index ix_shipments_status on shipments (status);
create index ix_shipments_trip on shipments (trip_id);
create index ix_shipments_stop on shipments (stop_id);
create index ix_shipments_deadline on shipments (deadline);

create table delivery_proofs (
    id              bigserial primary key,
    stop_id         bigint      not null references trip_stops (id),
    recipient_name  varchar(120) not null,
    note            varchar(500),
    lat             double precision,
    lng             double precision,
    captured_by     varchar(60),
    captured_at     timestamptz not null
);

-- ---------------------------------------------------------------- tracking
create table vehicle_locations (
    id           bigserial primary key,
    vehicle_id   bigint      not null references vehicles (id),
    trip_id      bigint      references trips (id) on delete set null,
    recorded_at  timestamptz not null,
    lat          double precision not null,
    lng          double precision not null,
    speed_kph    real
);
create index ix_locations_vehicle_time on vehicle_locations (vehicle_id, recorded_at);
create index ix_locations_trip_time on vehicle_locations (trip_id, recorded_at);

create table status_history (
    id           bigserial primary key,
    entity_type  varchar(20) not null,
    entity_id    bigint      not null,
    from_status  varchar(20),
    to_status    varchar(20) not null,
    reason       varchar(255),
    actor        varchar(60),
    changed_at   timestamptz not null
);
create index ix_status_history_entity on status_history (entity_type, entity_id, changed_at);

-- ---------------------------------------------------------------- risk
create table risk_rules (
    code         varchar(30) primary key,
    label        varchar(120) not null,
    description  varchar(400) not null,
    points       integer not null check (points >= 0),
    threshold    double precision,
    unit         varchar(20),
    enabled      boolean not null default true,
    sort_order   integer not null default 0,
    updated_at   timestamptz not null default now(),
    updated_by   varchar(60)
);

create table system_settings (
    key          varchar(60) primary key,
    value        varchar(255) not null,
    label        varchar(120) not null,
    description  varchar(400) not null,
    updated_at   timestamptz not null default now(),
    updated_by   varchar(60)
);

create table risk_evaluations (
    id            bigserial primary key,
    trip_id       bigint      not null references trips (id) on delete cascade,
    evaluated_at  timestamptz not null,
    score         integer     not null,
    band          varchar(10) not null,
    reasons       text        not null
);
create index ix_risk_eval_trip on risk_evaluations (trip_id, evaluated_at);

create table sla_breach_events (
    id            bigserial primary key,
    shipment_id   bigint      not null references shipments (id),
    trip_id       bigint      references trips (id),
    deadline      timestamptz not null,
    breached_at   timestamptz not null,
    minutes_late  integer     not null,
    kind          varchar(20) not null check (kind in ('LATE_DELIVERY', 'FAILED', 'DEADLINE_PASSED')),
    unique (shipment_id, kind)
);

-- ---------------------------------------------------------------- incidents
create table incidents (
    id               bigserial primary key,
    code             varchar(20) not null unique,
    type             varchar(30) not null
        check (type in ('SLA_RISK', 'BREAKDOWN', 'LONG_STOP', 'ROUTE_DEVIATION', 'LOCATION_STALE',
                        'FAILED_DELIVERY', 'RESOURCE_UNAVAILABLE')),
    severity         varchar(10) not null check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status           varchar(20) not null default 'DETECTED'
        check (status in ('DETECTED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED')),
    trip_id          bigint references trips (id),
    shipment_id      bigint references shipments (id),
    vehicle_id       bigint references vehicles (id),
    title            varchar(200) not null,
    details          text,
    detected_at      timestamptz not null,
    last_detected_at timestamptz not null,
    detection_count  integer     not null default 1,
    acknowledged_at  timestamptz,
    owner_username   varchar(60),
    escalated        boolean     not null default false,
    resolved_at      timestamptz,
    resolution       varchar(500),
    condition_cleared boolean    not null default false,
    version          integer     not null default 0
);
-- Deduplication: one unresolved incident per (trip, type).
create unique index ux_incident_open on incidents (trip_id, type) where status <> 'RESOLVED';
create index ix_incidents_status on incidents (status, detected_at);

create table incident_actions (
    id            bigserial primary key,
    incident_id   bigint      not null references incidents (id) on delete cascade,
    action        varchar(30) not null,
    note          varchar(1000),
    actor         varchar(60) not null,
    performed_at  timestamptz not null
);
create index ix_incident_actions on incident_actions (incident_id, performed_at);

-- ---------------------------------------------------------------- notifications and audit
create table notifications (
    id           bigserial primary key,
    user_id      bigint      not null references users (id) on delete cascade,
    severity     varchar(10) not null,
    title        varchar(200) not null,
    body         varchar(500),
    link         varchar(200),
    created_at   timestamptz not null,
    read_at      timestamptz
);
create index ix_notifications_user on notifications (user_id, read_at, created_at);

create table audit_log (
    id           bigserial primary key,
    actor        varchar(60) not null,
    action       varchar(60) not null,
    entity_type  varchar(30) not null,
    entity_id    varchar(40),
    summary      varchar(300) not null,
    before_json  text,
    after_json   text,
    occurred_at  timestamptz not null
);
create index ix_audit_time on audit_log (occurred_at desc);
create index ix_audit_entity on audit_log (entity_type, entity_id);

create sequence trip_code_seq start 100;
create sequence incident_code_seq start 1000;
create sequence tracking_no_seq start 4100000;
