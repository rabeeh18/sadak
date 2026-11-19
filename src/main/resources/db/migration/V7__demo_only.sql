-- Sadak is demo-only: no Admin role, no email invites or password links.

-- Anyone who was only an admin becomes a dispatcher; then the Admin role goes.
insert into user_roles (user_id, role_id)
select distinct ur.user_id, 2 from user_roles ur
where ur.role_id = 1
  and not exists (select 1 from user_roles other where other.user_id = ur.user_id and other.role_id <> 1);
delete from user_roles where role_id = 1;
delete from roles where id = 1;

drop table if exists user_tokens;
