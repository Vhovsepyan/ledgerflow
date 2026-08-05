create table payment (
                         id             uuid         primary key,
                         merchant_id    uuid         not null references merchant (id),
                         merchant_ref   varchar(255),
                         amount_minor   bigint       not null,
                         fee_minor      bigint       not null,
                         currency       varchar(3)   not null,
                         status         varchar(32)  not null,
                         failure_reason varchar(255),
                         created_at     timestamptz  not null default now(),
                         updated_at     timestamptz  not null default now(),
                         version        bigint       not null default 0,
                         constraint payment_amount_positive check (amount_minor > 0),
                         constraint payment_fee_non_negative check (fee_minor >= 0),
                         constraint payment_fee_not_above_amount check (fee_minor <= amount_minor),
                         constraint payment_status_valid check (
                             status in ('CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELED', 'REFUNDED')
                             )
);

create index payment_merchant_idx on payment (merchant_id, created_at desc);
create index payment_status_idx on payment (status) where status not in ('CAPTURED', 'FAILED', 'CANCELED', 'REFUNDED');