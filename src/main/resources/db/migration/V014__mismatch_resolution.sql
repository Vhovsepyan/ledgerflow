alter table recon_mismatch add column resolved_by  varchar(255);
alter table recon_mismatch add column resolved_at  timestamptz;
alter table recon_mismatch add column resolution_note varchar(1000);