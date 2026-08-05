-- Sign convention: positive amount = DEBIT, negative amount = CREDIT.
-- Assets grow with debits. Liabilities and revenue grow with credits.

create table ledger_account (
                                id          uuid        primary key,
                                parent_id   uuid        references ledger_account (id),
                                account_key text        not null,
                                account_type varchar(20)    not null,
                                currency    varchar(3)     not null,
                                created_at  timestamptz not null default now(),
                                constraint ledger_account_key_unique unique (account_key),
                                constraint ledger_account_type_valid
                                    check (account_type in ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    -- lets ledger_entry reference (account, currency) as one unit
                                constraint ledger_account_id_currency_unique unique (id, currency)
);

create table ledger_transaction (
                                    id          uuid        primary key,
                                    reference   text        not null,
                                    description text        not null,
                                    created_at  timestamptz not null default now(),
                                    constraint ledger_transaction_reference_unique unique (reference)
);

create table ledger_entry (
                              id             uuid        primary key,
                              transaction_id uuid        not null references ledger_transaction (id),
                              account_id     uuid        not null,
                              currency       varchar(3)     not null,
                              amount_minor   bigint      not null,
                              created_at     timestamptz not null default now(),
                              constraint ledger_entry_amount_not_zero check (amount_minor <> 0),
    -- an entry can only touch an account of the same currency
                              constraint ledger_entry_account_currency_fk
                                  foreign key (account_id, currency) references ledger_account (id, currency)
);

create index ledger_entry_account_idx on ledger_entry (account_id);
create index ledger_entry_transaction_idx on ledger_entry (transaction_id);

-- Safety net 1: a transaction must balance to zero, per currency.
create or replace function ledger_transaction_must_balance()
    returns trigger
    language plpgsql
as $$
declare
    bad_currency varchar(3);
begin
select currency
into bad_currency
from ledger_entry
where transaction_id = new.transaction_id
group by currency
having sum(amount_minor) <> 0
limit 1;

if found then
        raise exception 'Ledger transaction % is unbalanced in currency %',
            new.transaction_id, bad_currency
            using errcode = 'check_violation';
end if;

return null;
end;
$$;

create constraint trigger ledger_entry_balance_check
    after insert on ledger_entry
                     deferrable initially deferred
    for each row
execute function ledger_transaction_must_balance();

-- Safety net 2: entries are history. Fix mistakes by posting a reversal.
create or replace function ledger_entry_is_immutable()
    returns trigger
    language plpgsql
as $$
begin
    raise exception 'Ledger entries are immutable; post a reversing transaction instead'
        using errcode = 'check_violation';
end;
$$;

create trigger ledger_entry_no_update_or_delete
    before update or delete on ledger_entry
    for each row
execute function ledger_entry_is_immutable();