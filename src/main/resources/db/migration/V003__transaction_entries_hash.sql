alter table ledger_transaction
    add column entries_hash varchar(64) not null default '';

alter table ledger_transaction
    alter column entries_hash drop default;