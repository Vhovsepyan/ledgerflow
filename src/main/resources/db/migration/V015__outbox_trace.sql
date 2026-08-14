alter table outbox_event add column trace_id varchar(64);
alter table outbox_event add column span_id  varchar(64);

alter table webhook_delivery add column trace_id varchar(64);