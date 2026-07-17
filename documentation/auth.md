# Authentication & Authorization

Tokens are stored as SHA-256 hashes in PostgreSQL. The plaintext is shown exactly once at creation and never stored. Auth responses are cached with Caffeine (60s TTL, max 1000 entries). This applies to api_tokens **and** OAuth access tokens: a revoked or expired token can therefore keep working for up to 60 seconds after revocation — the cache TTL is the revocation propagation window.

## Human auth vs. machine auth

HiveMem splits authentication by **path**, not by mode: humans (browsers) and machines (MCP
clients, hooks, peer sync, webhooks) never share an endpoint. `HumanAuthFilter` runs first
(`Ordered.HIGHEST_PRECEDENCE`) and resolves a human principal only for human paths; for every
machine path it passes the request straight through, unauthenticated, to `AuthFilter` — it does
not even attempt to read a session there. **This means `/mcp` no longer accepts a browser
session cookie under any circumstances** — the whole point of the split. A browser can only
reach tools through `/api/tools/call`.

Humans prove who they are one of two ways, depending on deployment mode:

| Mode | `hivemem.access.enabled` | Humans authenticate via | `/login`, `/logout` |
|---|---|---|---|
| **Access** (Cloudflare Access in front) | `true` | Cloudflare Access JWT (`Cf-Access-Jwt-Assertion` header) | disabled — return `410 Gone` |
| **Legacy** (self-hosted, default) | `false` | Session cookie via the `/login` form | active |

Legacy is a fully supported production mode, not a dev fallback — it's what self-hosted
deployments without Cloudflare use. See [Operations → Enabling Cloudflare Access](operations.md#enabling-cloudflare-access-human-auth-hardening)
for the rollout procedure and env vars.

In Access mode, the Access JWT only proves an email address; HiveMem still does its own
authorization. The email is looked up (case-insensitively) against an `email` column on
`api_tokens` — the same row a human's admin token lives on. A valid JWT for an email with no
matching, non-revoked, non-expired row is authenticated but not authorized: `403`, not `401`.
See [Operations](operations.md#enabling-cloudflare-access-human-auth-hardening) for how that
mapping is created (`hivemem-token set-email`).

## Which paths use which authentication

| Paths | Who | Authentication |
|---|---|---|
| `/api/**` (incl. `/api/tools/call`) | Human | Access JWT **or** session cookie (`HumanAuthFilter`); no principal → `401` |
| SPA routes (everything not matched below) | Human | Access JWT **or** session cookie; no principal → `302 /login` (legacy) or `403` page (access mode, no `/login` exists) |
| `/admin/**` except `/admin/peers**` | Human (browser) | Access JWT **or** session cookie; no principal → same as SPA routes |
| `/admin/peers**` | Machine (CLI, `connect-peers.sh`) | Bearer token — `HumanAuthFilter` defers to `AuthFilter` without attempting a human principal |
| `/mcp` | Machine | Bearer token from `api_tokens`, **or** an OAuth access token — never a session |
| `/hooks`, `/sync` (incl. `/sync/ops`) | Machine | Bearer token (`AuthFilter`) |
| `/vistierie/**` | Machine (webhook) | Controller-level webhook token (constant-time check), not `AuthFilter` |
| `/login`, `/logout` | Human | Public in legacy mode (rate-limited); `410 Gone` in access mode |
| `/oauth/authorize` | Human | Controller resolves its own principal (Access JWT, session, or the OAuth test attribute) |
| `/oauth/token`, `/oauth/register`, `/.well-known/oauth-*` | Machine | PKCE / DCR — public, no `HumanAuthFilter` involvement |
| `/api/config` | — | Public, unauthenticated; returns `{"authMode":"access"|"legacy"}` so the SPA can pick its re-auth strategy before making its first authenticated call |

`/api/tools/call` accepts the same JSON-RPC `tools/call` payload as `/mcp` and both are routed
through the same `ToolCallDispatcher` (permission check, realm filtering, embedding gate) — the
only difference between the two endpoints is who is allowed to call them and how they prove it.
A bearer `Authorization` header is **not** evaluated on `/api/**`; only an Access JWT or a
session cookie resolves a principal there.

Every `/admin` endpoint additionally enforces the `admin` role itself, and `/sync/ops` (both GET
and POST) additionally requires a `writer`- or `admin`-role token — peer tokens are issued as
`writer` (see `scripts/connect-peers.sh`), so this closes a `reader`/`agent`-token
committed-write bypass without breaking peer sync.

A realm-scoped human principal (an Access-mapped row with `read_realms`/`write_realms` set) is
confined to `/api/tools/call` — the only place realm enforcement happens for humans — and gets
`403` on every other `/api` path (`/api/gui/stream`, `/api/attachments`, …), mirroring the
existing bearer-side restriction that confines realm-scoped tokens to `/mcp`.

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
hivemem-token create <name> --role admin|writer|reader|agent [--expires 90d] \
                             [--read-realms a,b] [--write-realms a]
hivemem-token list
hivemem-token revoke <name>
hivemem-token info <name>
```

### Realm-scoped tokens

Every token can carry two optional, independent realm sets: `read_realms` and
`write_realms`. Both default to `NULL`, which means **unrestricted** — this is the
behavior every token had before this feature, so existing tokens are unaffected.

- **`read_realms`** — when set, the token's *tenant view*: realms outside the set are
  invisible to it. Realms not in the set do not show up in listings/search results at all,
  rather than erroring.
- **`write_realms`** — when set, *write confinement*: the token can only create/modify
  content in those realms; writes targeting a realm outside the set are rejected. Realm-
  bearing writes (`add_cell`, `update_blueprint`, `upload_attachment`) must name an explicit
  `realm` in the set — an omitted/blank realm is rejected (it would otherwise persist a
  null-realm cell that escapes the confinement), never silently defaulted.

A token can be scoped on one dimension and unrestricted on the other (e.g. read broadly,
write narrowly) — the two sets are independent.

Realm-scoped tokens are additionally **confined to `/mcp`** — they cannot authenticate
against `/sync/ops`, `/hooks/context`, or other non-MCP routes, regardless of role.

Within `/mcp`, enforcement differs by tool surface (v1):

- **Realm-filtered**: ordinary reads/writes (`search`, `add_cell`, `list`, etc.) are
  rewritten/filtered to the token's `read_realms`/`write_realms`.
- **Global, not realm-filtered**: the knowledge-graph triple surfaces — `search_kg` and
  `time_machine` — operate across all realms regardless of token scope.
- **Blocked (403) for scoped tokens in v1**: graph-traversal reads (`traverse`, `history`,
  `entity_overview`) and any report that mixes multiple realms. These are deferred to a
  later version rather than partially filtered, to avoid leaking cross-realm structure.

Create a realm-scoped token with the CLI:

```bash
hivemem-token create dracul-research-agent --role writer \
  --write-realms dracul-research --read-realms dracul-research,dracul
```

This mints a `writer` token that can read the `dracul-research` and `dracul` realms but
write only to `dracul-research`. Realm names passed via `--read-realms`/`--write-realms`
are comma-separated, lowercased, and spaces are turned into dashes to match server-side
normalization. After normalization each realm must match `^[a-z0-9-]+$` — this allowlist is
enforced identically by the CLI and by the token-creation API, so odd/empty realm strings
are rejected up front.

## OAuth 2.0 Connector Access

Beyond static bearer tokens, HiveMem ships a full OAuth 2.0 authorization server so MCP
clients (Claude.ai / ChatGPT / Grok Custom Connectors) can obtain their own scoped access
tokens. Google Gemini is **not** supported — Google does not currently offer custom MCP
connectors. It is **disabled by default**: while `hivemem.oauth.enabled=false`, all `/oauth/*` and
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

## Status codes

- **`401`** — no valid credential presented at all: missing/invalid bearer on a machine path,
  no resolvable human principal on `/api/**`, or an Access JWT that fails signature/`aud`/`iss`/
  `exp` verification (no detail is returned to the caller for JWT failures — only logged, to
  avoid an information leak).
- **`403`** — the caller *is* authenticated but not authorized: an Access JWT with a valid email
  that has no matching non-revoked, non-expired `api_tokens` row; a realm-scoped human principal
  outside `/api/tools/call`; a role that doesn't permit the requested tool/endpoint. A `403`
  deliberately does not send the human back into a login loop the way a `401` would.

## Security Details

- **Rate limiting** — 5 failed auth attempts per IP triggers a 15-minute ban. A request that
  carries **no** `Authorization` header at all is rejected but does **not** count toward the ban
  threshold — only a request presenting a header that fails validation counts as a real guessing
  attempt. This matters because `/mcp` now always requires a bearer: without this exemption, a
  stale client sessionlessly polling `/mcp` would ban its own IP (and everything else behind
  that IP, e.g. a shared connection) within a minute. Both the bearer
  and the login limiter key on the **real TCP peer address** (the servlet request is unwrapped
  past Spring's forwarded-header rewriting), so rotating `X-Forwarded-For` headers cannot evade
  the lockout. The trackers are Caffeine-bounded (entries expire with the ban window, capped
  size), so high-cardinality sources cannot grow the heap.
  - **Behind the Cloudflare Tunnel** (production), the TCP peer is loopback for every external
    request, so `hivemem.security.trusted-proxy` (default `true`, env `HIVEMEM_TRUSTED_PROXY`)
    makes both limiters key on the tunnel-injected `CF-Connecting-IP` header instead — that
    header cannot be spoofed by a client going through the tunnel, unlike `X-Forwarded-For`.
    Set it to `false` for deployments with no trusted reverse proxy in front (e.g. direct LAN
    access), which falls back to the raw TCP peer address.
- **Audit log** — every request logged to `/data/audit.log`
- **Timing-safe** — token comparison uses SHA-256 hash lookup, not string comparison
- **Path traversal protection** — file import restricted to `/data/imports` and `/tmp`
- **Tool call enforcement** — `tools/call` checked against role permissions, not just `tools/list` filtering
