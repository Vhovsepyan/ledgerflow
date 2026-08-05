create table idempotency_record (
                                    id              uuid         primary key,
                                    idempotency_key varchar(255) not null,
                                    merchant_id     uuid         not null references merchant (id),
                                    request_hash    varchar(64)  not null,
                                    status          varchar(16)  not null,
                                    response_status integer,
                                    response_body   text,
                                    payment_id      uuid,
                                    created_at      timestamptz  not null default now(),
                                    completed_at    timestamptz,
                                    constraint idempotency_key_unique unique (merchant_id, idempotency_key),
                                    constraint idempotency_status_valid check (status in ('IN_PROGRESS', 'COMPLETED'))
);

create index idempotency_created_idx on idempotency_record (created_at);