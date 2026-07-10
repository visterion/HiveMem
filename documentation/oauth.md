# OAuth 2.0 + Custom Connector Setup

HiveMem can be added to **Claude.ai** (and ChatGPT once OpenAI rolls out MCP Custom
Connector support) as a **Custom MCP Connector**. The user's chat with the model
will then search, read, and write to your HiveMem instance directly — without
leaving the chat UI and without an Anthropic-side store of your knowledge.

This document covers:

1. Enabling OAuth on the HiveMem server
2. Exposing the server publicly via Cloudflare Tunnel (recommended)
3. Adding HiveMem as a Custom Connector in Claude.ai
4. Verification
5. Security model and limits

## 1. Enable OAuth

Edit `application.yml` (or override via environment variables):

```yaml
hivemem:
  oauth:
    enabled: true
    issuer: https://hivemem.example.com   # MUST match the public HTTPS URL exactly
    access-token-ttl: PT1H                 # 1 hour
    refresh-token-ttl: P30D                # 30 days
    authorization-code-ttl: PT10M          # 10 minutes
    dynamic-client-registration-enabled: true
```

The `issuer` MUST be the exact URL the client reaches the discovery endpoint at —
mismatch will fail validation in Claude.ai.

Only `enabled` and `issuer` are required — the remaining keys have sensible defaults
(shown above). In production these two are wired to environment variables:
`HIVEMEM_OAUTH_ENABLED=true` and `HIVEMEM_OAUTH_ISSUER=https://hivemem.example.com`. See
[Operations → Enabling OAuth](operations.md#enabling-oauth-mcp-custom-connectors) for the
docker-compose deploy step.

OAuth is **disabled by default**. While `enabled` is `false`, every `/oauth/*` and
`/.well-known/oauth-*` endpoint returns `404`. A non-blank `issuer` is additionally
required for OAuth to *function* — with `enabled=true` but a blank issuer the discovery
documents respond `200` with an empty `issuer` string and the `/mcp` `WWW-Authenticate`
header is suppressed, so the connector flow will not work.

Spring is already configured for reverse-proxy correctness (see `application.yml`):

```yaml
server:
  forward-headers-strategy: framework
```

This honours `X-Forwarded-Proto` / `X-Forwarded-Host` set by Cloudflare Tunnel,
Nginx, Caddy, etc. so absolute URLs in OAuth responses come out as `https://...`
even though Spring sees a plain HTTP request internally.

## 2. Expose the server via Cloudflare Tunnel

Cloudflare Tunnel terminates TLS at Cloudflare's edge, requires no port forwarding
or firewall changes, and gives you a stable HTTPS URL backed by Cloudflare's
network.

```bash
# Install cloudflared (one-time)
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
     -o /usr/local/bin/cloudflared && chmod +x /usr/local/bin/cloudflared

cloudflared tunnel login
cloudflared tunnel create hivemem
cloudflared tunnel route dns hivemem hivemem.example.com

# Configure the tunnel:
cat > ~/.cloudflared/config.yml <<EOF
tunnel: <tunnel-uuid-from-create>
credentials-file: /root/.cloudflared/<tunnel-uuid>.json
ingress:
  - hostname: hivemem.example.com
    service: http://localhost:8421
  - service: http_status:404
EOF

cloudflared tunnel run hivemem
# Or, install as a systemd service:
#   cloudflared service install
```

## 3. Add HiveMem as a Custom Connector in Claude.ai

1. Open **Claude.ai → Settings → Connectors → Add Custom Connector**
2. Enter the URL of the MCP endpoint: `https://hivemem.example.com/mcp`
3. Claude.ai fetches `/.well-known/oauth-protected-resource` to discover the
   authorization server
4. Claude.ai fetches `/.well-known/oauth-authorization-server` to discover the
   authorization, token, and registration endpoints
5. Claude.ai POSTs to `/oauth/register` (Dynamic Client Registration, RFC 7591)
   to obtain a `client_id`
6. Claude.ai redirects you to `/oauth/authorize` — log in to HiveMem in your
   browser via the existing web-UI session if you are not already
7. `/oauth/authorize` renders a **consent screen** showing the requesting client
   and the scope being granted; a session-bound one-time CSRF token guards the
   confirmation. Only after you approve (POST `action=approve`) is an
   authorization code issued; denying (or a missing/invalid CSRF token) returns
   `error=access_denied`. The granted scope is capped to your token's role, so a
   reader session cannot mint a `write` code.
8. After approval, you are redirected back to Claude.ai with an authorization code
9. Claude.ai exchanges the code at `/oauth/token` for an access + refresh token
10. Subsequent MCP tool calls from Claude.ai authenticate with the access token;
    Claude.ai handles refresh-rotation automatically

You should now see HiveMem's tools available in your Claude.ai sidebar.

## 4. Verification

Discovery endpoint:

```bash
curl -s https://hivemem.example.com/.well-known/oauth-authorization-server | jq .
```

Expected output:

```json
{
  "issuer": "https://hivemem.example.com",
  "authorization_endpoint": "https://hivemem.example.com/oauth/authorize",
  "token_endpoint": "https://hivemem.example.com/oauth/token",
  "registration_endpoint": "https://hivemem.example.com/oauth/register",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "refresh_token"],
  "token_endpoint_auth_methods_supported": ["none"],
  "code_challenge_methods_supported": ["S256"],
  "scopes_supported": ["read", "write"]
}
```

`/mcp` is reachable but requires auth:

```bash
curl -i -X POST https://hivemem.example.com/mcp \
     -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

Expected: `HTTP/2 401`. When OAuth is enabled, the response also carries an RFC 9728
discovery header pointing MCP clients at the protected-resource metadata:

```
WWW-Authenticate: Bearer resource_metadata="https://hivemem.example.com/.well-known/oauth-protected-resource"
```

When OAuth is disabled, `/mcp` returns a bare `401` with no `WWW-Authenticate` header.

## 5. Security model

### Public clients only (PKCE mandatory)

Only `token_endpoint_auth_method=none` clients are accepted. There is no
`client_secret` to leak. Code exchange is bound to a per-request PKCE code
verifier (RFC 7636 S256). Clients that try to register as confidential
(`client_secret_basic`, etc.) are rejected with `invalid_client_metadata`.

### Refresh-token rotation with reuse detection

Refresh tokens rotate on every use (RFC 6819 §5.2.2.3). When an old refresh
token is presented after rotation, the entire chain rooted at it is revoked —
both the old and the new tokens become invalid. The connector must
re-authorize.

This frustrates attacks where an attacker has stolen a refresh token: the
moment the legitimate client uses it, the chain compromise is detected.

### OAuth-issued tokens cap at WRITER

OAuth-issued access tokens are capped at the `WRITER` role even when the
underlying api_token row has role `admin` or `agent`. A connector session can
read and write knowledge, but cannot create new tokens, manage agents, or
touch admin-only endpoints. This limits blast radius if a Claude.ai session
is compromised.

The cap is applied in `AuthFilter.resolveOauthPrincipal()` and tested in the
auth and OAuth integration suites.

### redirect_uri validation

- HTTPS always accepted
- HTTP only on loopback (`127.0.0.1`, `localhost`, `[::1]`) — for native CLI
  clients
- Custom schemes (e.g. `claude://`, `com.example.app://`) accepted for native
  apps
- `javascript:`, `file:`, `data:`, `vbscript:`, `about:` explicitly rejected
- URI fragments forbidden (RFC 6749 §3.1.2)

### Tokens stored hashed

Authorization codes, access tokens, and refresh tokens are SHA-256 hashed
before persistence. Plaintext is only ever returned to the legitimate client
once at issue time. A database leak does not expose usable bearers.

### Dynamic Client Registration

Open by default for personal use. Anyone reachable can register a client and
obtain a `client_id`. This is acceptable because:

- No `client_secret` is issued — registration alone confers no power
- The authorization code flow still requires a logged-in HiveMem user to
  approve the request via the standard web-UI session

For multi-tenant or higher-stakes deployments, set
`dynamic-client-registration-enabled: false` and pre-provision clients
directly via SQL into `oauth_clients`.

## 6. Internals reference

- Schema: `java-server/src/main/resources/db/migration/V0025__oauth.sql`
- Discovery: `OAuthDiscoveryController`
- Registration: `ClientRegistrationController` + `ClientRegistrationService`
- Authorize: `AuthorizationController` + `AuthorizationCodeService`
- Token: `TokenController` + `TokenEndpointService`
- AuthFilter integration: `AuthFilter.resolveOauthPrincipal()`
- Tests: `java-server/src/test/java/com/hivemem/oauth/` (PKCE, redirect URI,
  end-to-end)
