-- "Hasn't left yet": a planned trip past its departure time whose driver has not pressed Start trip.
-- Watch from the rule's threshold (10 min); Needs action, with an incident, from the setting (30 min).

alter table incidents drop constraint if exists incidents_type_check;
alter table incidents add constraint incidents_type_check
    check (type in ('SLA_RISK', 'BREAKDOWN', 'LONG_STOP', 'ROUTE_DEVIATION', 'LOCATION_STALE', 'NOT_DEPARTED'));

insert into risk_rules (code, label, description, points, threshold, unit, sort_order) values
('NOT_DEPARTED', 'Hasn''t left yet',
 'A planned trip is past its departure time and the driver has not pressed Start trip. A heads-up from the threshold; needs action from the minutes set below.',
 20, 10, 'min', 5);

insert into system_settings (key, value, label, description) values
('risk.not-departed-action-min', '30', 'Hasn''t left yet needs action from (min)',
 'A planned trip this many minutes past its departure time, still not started, makes it Needs action and opens a "Hasn''t left yet" incident.');
