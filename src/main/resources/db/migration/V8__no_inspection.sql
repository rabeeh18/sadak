-- Workshop visits are either planned (a scheduled service) or not (a breakdown).
-- Inspections become scheduled services, so the history and downtime totals stay the same.

update maintenance_records set kind = 'SCHEDULED' where kind = 'INSPECTION';
alter table maintenance_records drop constraint if exists maintenance_records_kind_check;
alter table maintenance_records add constraint maintenance_records_kind_check check (kind in ('BREAKDOWN', 'SCHEDULED'));
