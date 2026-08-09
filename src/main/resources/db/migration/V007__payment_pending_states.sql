alter table payment drop constraint payment_status_valid;

alter table payment add constraint payment_status_valid check (
    status in ('CREATED', 'AUTHORIZATION_PENDING', 'AUTHORIZED',
               'CAPTURE_PENDING', 'CAPTURED', 'FAILED', 'CANCELED', 'REFUNDED')
    );

alter table payment add column psp_reference         varchar(255);
alter table payment add column verification_attempts integer     not null default 0;
alter table payment add column next_verification_at  timestamptz;

create index payment_pending_verification_idx
    on payment (next_verification_at)
    where status in ('AUTHORIZATION_PENDING', 'CAPTURE_PENDING');