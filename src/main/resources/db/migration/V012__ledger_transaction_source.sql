alter table ledger_transaction add column source_type varchar(32);
alter table ledger_transaction add column source_id   uuid;
alter table ledger_transaction add column source_operation varchar(32);

create index ledger_transaction_source_idx on ledger_transaction (source_type, source_id);

-- Backfill from the existing reference format.
update ledger_transaction
set source_type = 'payment',
    source_id = split_part(reference, ':', 2)::uuid,
       source_operation = split_part(reference, ':', 3)
where reference like 'payment:%:%';