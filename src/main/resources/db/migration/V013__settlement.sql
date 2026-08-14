create table settlement_batch (
                                  id               uuid         primary key,
                                  run_id           uuid         not null references recon_run (id),
                                  settlement_date  date         not null,
                                  currency         varchar(3)   not null,
                                  total_minor      bigint       not null,
                                  payment_count    integer      not null,
                                  ledger_transaction_id uuid,
                                  created_at       timestamptz  not null default now(),
                                  constraint settlement_batch_unique unique (settlement_date, currency),
                                  constraint settlement_batch_total_positive check (total_minor > 0)
);