create table settlement_batches (
    id        bigserial primary key,
    closed_at timestamptz not null
);

create table settlement_payments (
    event_id    uuid primary key,
    payment_id  bigint not null,
    booking_id  bigint not null,
    slot_id     bigint not null,
    instructor  text not null,
    kind        text not null,
    amount      numeric(12, 2) not null,
    occurred_at timestamptz not null,
    batch_id    bigint references settlement_batches (id),
    received_at timestamptz not null default now()
);

create index idx_settlement_payments_batch_id on settlement_payments (batch_id);

create table settlement_lines (
    id         bigserial primary key,
    batch_id   bigint not null references settlement_batches (id) on delete cascade,
    instructor text not null,
    charges    numeric(12, 2) not null,
    refunds    numeric(12, 2) not null,
    payout     numeric(12, 2) not null
);

create index idx_settlement_lines_batch on settlement_lines (batch_id);
