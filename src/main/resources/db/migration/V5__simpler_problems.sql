-- A simpler problem model.
-- Trips show one of three lights: LOW = On track, MEDIUM = Watch, HIGH = Needs action (CRITICAL is retired).
-- Incidents have three states: DETECTED = Open, IN_PROGRESS = Being handled, RESOLVED = Closed.
-- Failed deliveries are no longer incidents: the warehouse desk rebooks them.

update trips set risk_band = 'HIGH' where risk_band = 'CRITICAL';
update risk_evaluations set band = 'HIGH' where band = 'CRITICAL';
update incidents set severity = 'HIGH' where severity = 'CRITICAL';
update notifications set severity = 'HIGH' where severity = 'CRITICAL';

update incidents set status = 'IN_PROGRESS' where status = 'ACKNOWLEDGED';

delete from incident_actions where incident_id in (select id from incidents where type in ('FAILED_DELIVERY', 'RESOURCE_UNAVAILABLE'));
delete from incidents where type in ('FAILED_DELIVERY', 'RESOURCE_UNAVAILABLE');

-- Plain names for the rules; every rule is either a heads-up (Watch) or a problem that needs action.
update risk_rules set label = 'Will be late',
  description = 'A parcel will arrive after its promised time. Needs action from the minutes set below; points per minute late (threshold), up to the maximum.'
  where code = 'PROJECTED_LATE';
update risk_rules set label = 'Cutting it close',
  description = 'Heads-up: a parcel will arrive within the threshold of its promised time, or only a few minutes after it.'
  where code = 'DEADLINE_PRESSURE';
update risk_rules set label = 'Truck stuck on the road',
  description = 'The truck has not moved for longer than the threshold while away from any stop.'
  where code = 'UNEXPECTED_STOP';
update risk_rules set label = 'GPS lost',
  description = 'No position received for longer than the threshold.'
  where code = 'LOCATION_STALE';
update risk_rules set label = 'Off route',
  description = 'The truck is further than the threshold from its planned road.'
  where code = 'ROUTE_DEVIATION';
update risk_rules set label = 'Truck stuck at a stop',
  description = 'The truck has been at a stop longer than planned plus the threshold.'
  where code = 'LONG_STOP';
update risk_rules set label = 'Late before',
  description = 'Heads-up: a parcel on board was already late or re-attempted once.'
  where code = 'PRIOR_DELAYS';
update risk_rules set label = 'Breakdown',
  description = 'The driver reported a breakdown.'
  where code = 'BREAKDOWN';

delete from system_settings where key in ('risk.band.medium', 'risk.band.high', 'risk.band.critical', 'incident.sla-min-band');
update system_settings set label = 'Cutting it close (min)',
  description = 'A parcel arriving within this many minutes of its promised time is a heads-up (Watch).'
  where key = 'sla.at-risk-min';
insert into system_settings (key, value, label, description) values
('risk.late-action-min', '5', 'Late needs action from (min)',
 'A parcel this many minutes or more past its promised time makes the trip Needs action and opens a "Will be late" incident.');
