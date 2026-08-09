create table psp_attempt (
                             id             uuid         primary key,
                             payment_id     uuid         not null references payment (id),
                             operation      varchar(32)  not null,
                             attempt_no     integer      not null,
                             outcome        varchar(32)  not null,
                             psp_reference  varchar(255),
                             detail         varchar(500),
                             latency_ms     bigint       not null,
                             created_at     timestamptz  not null default now(),
                             constraint psp_attempt_operation_valid check (operation in ('AUTHORIZE', 'CAPTURE', 'LOOKUP')),
                             constraint psp_attempt_outcome_valid check (
                                 outcome in ('AUTHORIZED', 'CAPTURED', 'DECLINED', 'FAILED', 'UNKNOWN')
                                 )
);

create index psp_attempt_payment_idx on psp_attempt (payment_id, created_at);