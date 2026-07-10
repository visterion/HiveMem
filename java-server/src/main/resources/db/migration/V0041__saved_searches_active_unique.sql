-- The (owner, name) upsert in SavedSearchRepository had no uniqueness guarantee,
-- so concurrent saves could leave duplicate active rows. Soft-delete all but the
-- newest active row per (owner, name), then enforce a partial unique index so a
-- racing save fails loudly instead of duplicating.

UPDATE saved_searches s
SET valid_until = now()
WHERE s.valid_until IS NULL
  AND EXISTS (
      SELECT 1 FROM saved_searches n
      WHERE n.owner = s.owner
        AND n.name = s.name
        AND n.valid_until IS NULL
        AND (n.created_at > s.created_at
             OR (n.created_at = s.created_at AND n.id > s.id))
  );

CREATE UNIQUE INDEX uq_saved_searches_owner_name_active
    ON saved_searches (owner, name) WHERE valid_until IS NULL;
