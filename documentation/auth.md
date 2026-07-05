# Authentication & Authorization

Tokens are stored as SHA-256 hashes in PostgreSQL. The plaintext is shown exactly once at creation and never stored. Auth responses are cached with Caffeine (60s TTL, max 1000 entries).

## Roles

Each token has one of four roles. The role controls which tools the client sees in `tools/list` and which it can call.

| Role | Visible tools | Write behavior | Can approve? |
|---|---|---|---|
| `admin` | All 34 | `status: committed` | Yes |
| `writer` | 32 (no admin tools) | `status: committed` | No |
| `reader` | 17 (read only) | Can't write | No |
| `agent` | 32 (same as writer) | `status: pending` | No |

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
   the normal HiveMem web-UI session and approve the request.
3. **Token** — the client exchanges the returned authorization code at `/oauth/token` for an
   access token and a refresh token (refresh tokens rotate on every use).
4. **Access `/mcp`** — subsequent MCP calls send the access token as a bearer; the client
   handles refresh-rotation automatically.

### OAuth principals are capped at WRITER

An OAuth-issued access token is validated against the `oauth_tokens` table in
`AuthFilter.resolveOauthPrincipal()` and its effective role is **capped at `writer`**, even when
the underlying api_token row is `admin` or `agent`. A connector session can read and write
knowledge but can never create tokens, manage agents, or reach admin-only endpoints. This limits
blast radius if a connector session is compromised.

### `WWW-Authenticate` on `/mcp` 401

When OAuth is enabled (with a configured issuer), an unauthenticated request to `/mcp` returns
`401` with an RFC 9728 discovery header so MCP clients can locate the authorization server:

```
WWW-Authenticate: Bearer resource_metadata="<issuer>/.well-known/oauth-protected-resource"
```

When OAuth is disabled, `/mcp` returns a **bare `401`** with no `WWW-Authenticate` header. Other
guarded paths never emit this header.

## Security Details

- **Rate limiting** — 5 failed auth attempts per IP triggers a 15-minute ban
- **Audit log** — every request logged to `/data/audit.log`
- **Timing-safe** — token comparison uses SHA-256 hash lookup, not string comparison
- **Path traversal protection** — file import restricted to `/data/imports` and `/tmp`
- **Tool call enforcement** — `tools/call` checked against role permissions, not just `tools/list` filtering
