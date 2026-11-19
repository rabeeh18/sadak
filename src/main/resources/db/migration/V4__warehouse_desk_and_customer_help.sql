-- Warehouse role, warehouse desk phones, customer notes for drivers, and customer complaints.

insert into roles (id, name) values (4, 'WAREHOUSE');
select setval(pg_get_serial_sequence('roles', 'id'), (select max(id) from roles));

-- A Warehouse account belongs to exactly one warehouse; other roles have none.
alter table users add column warehouse_id bigint references warehouses (id);

-- The desk number customers see on the tracking page, and its opening hours (local time).
alter table warehouses add column desk_phone  varchar(20);
alter table warehouses add column desk_opens  time;
alter table warehouses add column desk_closes time;

-- One note per shipment from the customer to the driver, shown at the stop.
alter table shipments add column customer_note    varchar(200);
alter table shipments add column customer_note_at timestamptz;

create table complaints (
    id                bigserial primary key,
    code              varchar(20)  not null unique,
    shipment_id       bigint       not null references shipments (id),
    warehouse_id      bigint       not null references warehouses (id),
    category          varchar(20)  not null
        check (category in ('LATE', 'NOT_RECEIVED', 'DAMAGED', 'WRONG_ADDRESS', 'DRIVER', 'OTHER')),
    message           varchar(500) not null,
    callback_phone    varchar(20)  not null,
    status            varchar(20)  not null default 'NEW' check (status in ('NEW', 'IN_PROGRESS', 'RESOLVED')),
    created_at        timestamptz  not null,
    handled_by        varchar(60),
    taken_at          timestamptz,
    sent_to_dispatch  boolean      not null default false,
    reply             varchar(500),
    resolved_at       timestamptz,
    version           integer      not null default 0
);
-- One open complaint per shipment.
create unique index ux_complaint_open on complaints (shipment_id) where status <> 'RESOLVED';
create index ix_complaints_warehouse on complaints (warehouse_id, status, created_at);
create sequence complaint_code_seq start 1;
