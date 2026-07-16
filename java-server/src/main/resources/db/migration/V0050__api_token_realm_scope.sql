-- V0050: per-token realm ACL. Two nullable text[] columns on api_tokens.
-- NULL = unrestricted (today's behavior) — every existing token keeps NULL and
-- is unaffected. read_realms limits which realms the token can see/read (tenant
-- view); write_realms limits which realms it can write. Enforced in AuthFilter
-- (route-guard) + McpController/ToolPermissionService (per-tool). See
-- docs/superpowers/specs/2026-07-16-realm-scoped-tokens-design.md.
ALTER TABLE api_tokens ADD COLUMN IF NOT EXISTS read_realms  TEXT[] NULL;
ALTER TABLE api_tokens ADD COLUMN IF NOT EXISTS write_realms TEXT[] NULL;

COMMENT ON COLUMN api_tokens.read_realms  IS
    'If set, token may only read/see these realms (tenant view). NULL = unrestricted.';
COMMENT ON COLUMN api_tokens.write_realms IS
    'If set, token may only write to these realms. NULL = unrestricted.';
