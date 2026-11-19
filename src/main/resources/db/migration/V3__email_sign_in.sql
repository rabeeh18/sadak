-- Sign-in by email, invite-only accounts and password reset links.

alter table users add column email varchar(254);
update users set email = username || '@sadak.example';
alter table users alter column email set not null;
create unique index ux_users_email on users (lower(email));

-- Null until the person has chosen a password from their invite link.
alter table users add column password_set_at timestamptz;
update users set password_set_at = created_at;

-- One-time links. Only a SHA-256 hash of each token is stored. Expiry uses real time, never the simulated clock.
create table user_tokens (
    id          bigserial primary key,
    user_id     bigint      not null references users (id) on delete cascade,
    purpose     varchar(10) not null check (purpose in ('INVITE', 'RESET')),
    token_hash  varchar(64) not null unique,
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    used_at     timestamptz
);
create index ix_user_tokens_user on user_tokens (user_id, created_at);
