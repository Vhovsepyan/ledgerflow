create table webhook_endpoint (
                                  id          uuid         primary key,
                                  merchant_id uuid         not null references merchant (id),
                                  url         varchar(500) not null,
                                  secret      varchar(255) not null,
                                  active      boolean      not null default true,
                                  created_at  timestamptz  not null default now(),
                                  constraint webhook_endpoint_unique unique (merchant_id, url)
);

create table webhook_delivery (
                                  id            uuid         primary key,
                                  endpoint_id   uuid         not null references webhook_endpoint (id),
                                  event_id      uuid         not null,
                                  event_type    varchar(64)  not null,
                                  payload       text         not null,
                                  status        varchar(16)  not null,
                                  attempts      integer      not null default 0,
                                  next_retry_at timestamptz,
                                  last_status   integer,
                                  last_error    varchar(500),
                                  created_at    timestamptz  not null default now(),
                                  delivered_at  timestamptz,
                                  constraint webhook_delivery_status_valid check (status in ('PENDING', 'DELIVERED', 'DEAD')),
                                  constraint webhook_delivery_unique unique (endpoint_id, event_id)
);

create index webhook_delivery_due_idx
    on webhook_delivery (next_retry_at)
    where status = 'PENDING';