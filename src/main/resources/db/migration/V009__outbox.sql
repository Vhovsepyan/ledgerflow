create table outbox_event (
                              id             uuid         primary key,
                              sequence_no    bigserial    not null,
                              aggregate_type varchar(64)  not null,
                              aggregate_id   uuid         not null,
                              event_type     varchar(64)  not null,
                              payload        text         not null,
                              created_at     timestamptz  not null default now(),
                              published_at   timestamptz,
                              publish_attempts integer    not null default 0,
                              last_error     varchar(500)
);

create index outbox_unpublished_idx
    on outbox_event (sequence_no)
    where published_at is null;