-- Reconciliation scans captures over a bounded time window. Without this the
-- window is honest about memory but still reads the whole table.
create index ledger_transaction_capture_time_idx
    on ledger_transaction (created_at)
    where source_operation = 'capture';
