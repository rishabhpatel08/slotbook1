-- Module migrations use their own version range: waitlist owns 200.x.

create table waitlist_entries (
    id         bigserial primary key,
    slot_id    bigint not null,
    member_id  bigint not null,
    joined_at  timestamptz not null default now(),
    unique (slot_id, member_id)
);

create index idx_waitlist_entries_slot on waitlist_entries (slot_id, id);

-- Idempotency table for at-least-once event delivery.
create table waitlist_processed_events (
    event_id    uuid primary key,
    processed_at timestamptz not null default now()
);
