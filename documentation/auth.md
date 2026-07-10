# Authentication & Authorization

Tokens are stored as SHA-256 hashes in PostgreSQL. The plaintext is shown exactly once at creation and never stored. Auth responses are cached with Caffeine (60s TTL, max 1000 entries). This applies to api_tokens **and** OAuth access tokens: a revoked or expired token can therefore keep working for up to 60 seconds after revocation — the cache TTL is the revocation propagation window.

## Which paths use which authentication

| Paths | Authentication |
|---|---|
| `/api/**` | Session cookie only (`SessionAuthFilter`); no session → `401` |
| `/admin/**` | Session cookie (browser), **or** bearer token when an `Authorization` header is present (CLI/scripts, e.g. `connect-peers.sh` → `/admin/peers`); no session and no bearer → redirect to `/login` |
| `/mcp`, `/hooks`, `/sync` | Bearer token (`AuthFilter`); used by MCP clients, hooks, and peer sync |
| `/vistierie/**` | Controller-level webhook token (constant-time check) |
| `/login`, `/oauth/*`, `/.well-known/oauth-*` | Public (the OAuth authorize endpoint resolves the user itself) |

`SessionAuthFilter` runs first and passes bearer-authenticated paths (including `/sync` — peer
replication would otherwise be redirected to the login page) through to `AuthFilter`, which
validates the bearer token or returns `401`. Every `/admin` endpoint additionally enforces the
`admin` role itself.

## Roles

Each token has one of four roles. The role controls which tools the client sees in `tools/list` and which it can call.

| Role | Visible tools | Write behavior | Can approve? |
|---|---|---|---|
| `admin` | All 47 | `status: committed` | Yes |
| `writer` | 43 (no admin tools) | `status: committed` | No |
| `reader` | 23 (read only) | Can't write | No |
| `agent` | 43 (same as writer) | `status: pending` | No |

The `agent` role is the key constraint: agents can add knowledge, but every write goes into a pending queue. Only an admin can approve or reject it. This prevents any agent from writing and self-approving in the same session.

`created_by` is set automatically from the token name. Clients can't override it.

## Token Management

The `hivemem-token` CLI is included in the Docker image:

```bash
docker exec hivemem hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d]
```

Available commands:

```bash
hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d]
hivemem-token list
hivemem-token revoke <name>
hivemem-token info <name>
```

## OAuth 2.0 Connector Access

Beyond static bearer tokens, HiveMem ships a full OAuth 2.0 authorization server so MCP
clients (Claude.ai / ChatGPT Custom Connectors) can obtain their own scoped access tokens.
It is **disabled by default**: while `hivemem.oauth.enabled=false`, all `/oauth/*` and
`/.well-known/oauth-*` endpoints return `404`. A public HTTPS `issuer` must also be configured
for the flow to actually work — it feeds the discovery metadata and the `WWW-Authenticate`
header (see below). See [OAuth 2.0 + Custom Connector Setup](oauth.md) for the full setup,
and [Operations → Enabling OAuth](operations.md#enabling-oauth-mcp-custom-connectors) for the
production enable step.

### Connector flow

1. **Register** — the client POSTs to `/oauth/register` (Dynamic Client Registration) and
   receives a `client_id`. Public clients only: `token_endpoint_auth_methods_supported=["none"]`,
   so no `client_secret` is issued — PKCE (S256, mandatory) plus a registered `redirect_uri`
   provide the security.
2. **Authorize** — the client redirects the user to `/oauth/authorize`, where they log in via
   the normal HiveMem web-UI session and approve the request on an explicit consent page
   (client name + requested scope). Only the consent form's POST — protected by a
   session-bound one-time anti-CSRF token — issues the authorization code; a plain GET never
   does. Denying redirects back to the client with `error=access_denied`.
3. **Token** — the client exchanges the returned authorization code at `/oauth/token` for an
   access token and a refresh token (refresh tokens rotate on every use).
4. **Access `/mcp`** — subsequent MCP calls send the access token as a bearer; the client
   handles refresh-rotation automatically.

### OAuth effective role: minimum of scope and backing token

An OAuth-issued access token is validated against the `oauth_tokens` table in
`AuthFilter.resolveOauthPrincipal()`. Its effective role is the **minimum** of the granted scope
and the backing api_token row's role — the scope can only ever narrow the backing role, never
widen it:

- a `reader` token stays `reader` even if `scope=write` was somehow granted;
- an `agent` token keeps its pending-write semantics (`agent`, never `writer`); a read-only
  scope narrows it to `reader`;
- `admin` is additionally **capped at `writer`** — a connector session can read and write
  knowledge but can never create tokens, manage agents, or reach admin-only endpoints. This
  limits blast radius if a connector session is compromised.

The same constraint is applied at authorize time: `/oauth/authorize` strips `write` from the
granted scope when the logged-in user's token is `reader` (the token response's `scope` field
reflects what was actually granted).

OAuth bearer lookups are served from a 60s Caffeine cache keyed by token hash (like api_tokens),
so revoking an OAuth token takes effect within at most 60 seconds.

### `WWW-Authenticate` on `/mcp` 401

When OAuth is enabled (with a configured issuer), an unauthenticated request to `/mcp` returns
`401` with an RFC 9728 discovery header so MCP clients can locate the authorization server:

```
WWW-Authenticate: Bearer resource_metadata="<issuer>/.well-known/oauth-protected-resource"
```

When OAuth is disabled, `/mcp` returns a **bare `401`** with no `WWW-Authenticate` header. Other
guarded paths never emit this header.

## Security Details

- **Rate limiting** — 5 failed auth attempts per IP triggers a 15-minute ban. Both the bearer
  and the login limiter key on the **real TCP peer address** (the servlet request is unwrapped
  past Spring's forwarded-header rewriting), so rotating `X-Forwarded-For` headers cannot evade
  the lockout. The trackers are Caffeine-bounded (entries expire with the ban window, capped
  size), so high-cardinality sources cannot grow the heap.
- **Audit log** — every request logged to `/data/audit.log`
- **Timing-safe** — token comparison uses SHA-256 hash lookup, not string comparison
- **Path traversal protection** — file import restricted to `/data/imports` and `/tmp`
- **Tool call enforcement** — `tools/call` checked against role permissions, not just `tools/list` filtering
