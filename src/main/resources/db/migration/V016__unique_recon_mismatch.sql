create unique index recon_mismatch_open_unique
    on recon_mismatch (reference, mismatch_type)
    where status = 'OPEN';