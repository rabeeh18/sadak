-- Rule-based risk scoring: points per condition. Editable by admins on the Settings page.
insert into risk_rules (code, label, description, points, threshold, unit, sort_order) values
('PROJECTED_LATE',    'Projected late',
 'Points per projected minute late on the worst remaining shipment, capped at 45. Threshold is the per-minute rate.', 45, 1.5, 'pts/min', 10),
('DEADLINE_PRESSURE', 'Deadline pressure',
 'A remaining shipment is not yet late but its ETA is within the threshold of its deadline.', 15, 15, 'min', 20),
('UNEXPECTED_STOP',   'Unexpected stop',
 'Vehicle has not moved for longer than the threshold while away from any stop.', 25, 8, 'min', 30),
('LOCATION_STALE',    'Location stale',
 'No location update received for longer than the threshold.', 30, 5, 'min', 40),
('ROUTE_DEVIATION',   'Off planned route',
 'Vehicle is further than the threshold from its planned route.', 20, 500, 'm', 50),
('LONG_STOP',         'Long stop',
 'Vehicle has been at a stop longer than its planned service time plus the threshold.', 15, 10, 'min', 60),
('PRIOR_DELAYS',      'History of delays',
 'One or more shipments on this trip have already been delayed or re-attempted.', 10, null, null, 70),
('BREAKDOWN',         'Vehicle breakdown',
 'The vehicle has been reported broken down.', 60, null, null, 80);

insert into system_settings (key, value, label, description) values
('risk.band.medium',          '25',  'Medium band from',     'Scores at or above this value are medium risk.'),
('risk.band.high',            '50',  'High band from',       'Scores at or above this value are high risk.'),
('risk.band.critical',        '75',  'Critical band from',   'Scores at or above this value are critical risk.'),
('sla.at-risk-min',           '15',  'SLA at-risk margin (min)', 'A shipment whose ETA is within this many minutes of its deadline is at risk.'),
('incident.sla-min-band',     'HIGH','SLA incident from band','Trips at or above this risk band open an SLA incident.'),
('tracking.geofence-m',       '120', 'Arrival radius (m)',   'A vehicle within this distance of its next stop is marked as arrived.'),
('tracking.persist-every-s',  '60',  'Store location every (sim s)', 'Location history is stored at most once per vehicle per this many simulated seconds.'),
('tracking.retention-days',   '30',  'Location retention (days)', 'Location history older than this is deleted nightly.'),
('sim.speed',                 '30',  'Simulation speed',     'How many simulated seconds pass per real second.'),
('sim.paused',                'false','Simulation paused',   'When true, simulated time and vehicles stand still.'),
('sim.clock',                 '',    'Simulation clock',     'Last saved simulated time, restored on restart.');
