create table recon_run (
                           id               uuid         primary key,
                           settlement_date  date         not null,
                           status           varchar(16)  not null,
                           lines_read       integer      not null default 0,
                           matched          integer      not null default 0,
                           mismatched       integer      not null default 0,
                           pending_timing   integer      not null default 0,
                           started_at       timestamptz  not null default now(),
                           finished_at      timestamptz,
                           error            varchar(500),
                           constraint recon_run_status_valid check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

create table recon_mismatch (
                                id               uuid         primary key,
                                run_id           uuid         not null references recon_run (id),
                                payment_id       uuid,
                                reference        varchar(255) not null,
                                mismatch_type    varchar(32)  not null,
                                provider_amount_minor bigint,
                                ledger_amount_minor   bigint,
                                currency         varchar(3),
                                evidence         text,
                                suggestion       varchar(500),
                                status           varchar(16)  not null default 'OPEN',
                                created_at       timestamptz  not null default now(),
                                constraint recon_mismatch_type_valid check (
                                    mismatch_type in ('MISSING_IN_LEDGER', 'MISSING_IN_PROVIDER', 'AMOUNT_MISMATCH')
                                    ),
                                constraint recon_mismatch_status_valid check (status in ('OPEN', 'RESOLVED', 'IGNORED'))
);

create index recon_mismatch_open_idx on recon_mismatch (created_at) where status = 'OPEN';