-- Human identity for Cloudflare Access mode: the Access JWT carries an email,
-- which maps to the api_tokens row holding role + realm ACL. Machine tokens keep
-- email NULL. Index on lower(email): Access may deliver mixed case, and a raw-column
-- index would allow 'A@b.de' and 'a@b.de' as two distinct "unique" identities.
-- The predicate cannot include expires_at (not immutable), so the assignment command
-- must revoke any prior non-revoked row carrying the same email.
ALTER TABLE api_tokens ADD COLUMN email TEXT;

CREATE UNIQUE INDEX api_tokens_email_active_idx
  ON api_tokens (lower(email))
  WHERE email IS NOT NULL AND revoked_at IS NULL;
