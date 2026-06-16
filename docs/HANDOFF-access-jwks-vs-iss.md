# Handoff: Cloudflare Access JWKS-host ≠ token-iss bug in `AccessJwtVerifier.forTeam`

**For:** the agent owning tools→console Access auth (single quarterback).
**From:** the agent that fixed the *same* bug in monitoring.buoy.fish (Grafana).
**Date:** 2026-06-16. **Status:** console side NOT changed — diagnosis + fix design only.

> **UPDATE 2026-06-16 (supersedes the `royal-waterfall` claims below):** the
> fix shipped (`AccessJwtVerifier.forTeam(issuerDomain, jwksDomain, audience)` +
> `helium.cf.access.jwks.domain`), AND Cloudflare has since completed the team
> rename. Console Access tokens now carry `iss=https://buoy-fish.cloudflareaccess.com`
> signed by buoy-fish keys — issuer AND JWKS are both **buoy-fish**, so there is
> **no live split today**. The `royal-waterfall` references below are historical
> (the transient migration state); both config defaults and the box are now
> `buoy-fish`. The two-knob decoupling was kept as defense in case the labels
> ever diverge again. The monitoring/Grafana box may still wrongly pin
> `royal-waterfall` and need the same correction — verify by decoding a live token.

---

## TL;DR

This Cloudflare Access account has **two team-domain labels for the same team**:

| Label | Reachable? | Role |
|---|---|---|
| `royal-waterfall-9e8e.cloudflareaccess.com` | **certs/login 404** | original auto-assigned name; **still stamped into every token's `iss`** |
| `buoy-fish.cloudflareaccess.com` | **200** | custom domain added later; **only host serving the JWKS/signing keys** |

So any Access-JWT validator must use **JWKS host = `buoy-fish`** and **expected `iss` = `royal-waterfall`** — *different domains, on purpose*. Code that derives the JWKS URL from the issuer (or assumes one team domain for both) **cannot fetch keys → 404 → all verification fails.**

`AccessJwtVerifier.forTeam()` does exactly that and is the latent landmine. It is **not breaking today** because the console shim is *match-only* (signature validation is deferred tier-3 per `sign.buoy.fish/docs/adr/0003`). It **will** break the instant tier-3 / real JWKS verification is switched on.

## Evidence (already gathered — don't re-derive)

- `curl https://royal-waterfall-9e8e.cloudflareaccess.com/cdn-cgi/access/certs` → **404**.
- `curl https://buoy-fish.cloudflareaccess.com/cdn-cgi/access/certs` → **200** (kids `d80d1eed…`, `0da27153…`).
- `https://buoy-fish.cloudflareaccess.com/.well-known/openid-configuration` → `issuer: https://buoy-fish…`, `jwks_uri: https://buoy-fish…/cdn-cgi/access/certs`. **NB: discovery's `issuer` disagrees with reality.**
- A **live decoded `Cf-Access-Jwt-Assertion`** (captured off `monitoring` box `lo:3000`, plaintext nginx→app) carried: `iss = https://royal-waterfall-9e8e.cloudflareaccess.com`, `aud = ["7cacba06…c44b"]`, `type = app`, `email = jameson@buoy.fish`. Signed by kid `0da27153…`, which lives in **buoy-fish's** JWKS — i.e. **royal-waterfall-iss token verifies against buoy-fish keys** (same account keys; only the domain label is stale). Trust the decoded token, not the discovery doc.
- To re-verify a live `iss` yourself (no Access login needed; nginx→app is plaintext on loopback): `sudo tcpdump -i lo -w /tmp/c.pcap 'tcp dst port <appPort>'`, then `strings` for `Cf-Access-Jwt-Assertion:` and base64url-decode segment 2.

## The bug, precisely

`src/main/java/eu/heliumiot/console/service/AccessJwtVerifier.java`:

```java
public static AccessJwtVerifier forTeam(String teamDomain, String audience) throws Exception {
    String iss = "https://" + teamDomain;
    JWKSource<SecurityContext> remote = JWKSourceBuilder
            .create(new URL(iss + "/cdn-cgi/access/certs"))   // <-- JWKS host derived from iss
            .retrying(true)
            .build();
    return new AccessJwtVerifier(remote, iss, audience);
}
```

`src/main/resources/application.properties:150`: `helium.cf.access.team.domain.default=royal-waterfall-9e8e.cloudflareaccess.com`.
With that value, the JWKS URL resolves to the 404 host. **A property-only flip to `buoy-fish` does NOT fix it** — it would fix JWKS but wrongly set `iss=buoy-fish`, and live tokens are `iss=royal-waterfall`. (This is the exact trap I fell into on the Grafana side before decoding a live token.)

Note the package-private constructor `AccessJwtVerifier(JWKSource, issuer, audience)` is **already decoupled** — only the `forTeam` factory couples the two. The existing test uses the constructor with an injected JWKSource, so it passes despite the bug.

## Suggested fix (decouple JWKS host from issuer)

Mirror the monitoring fix (merged-pending: **buoy-fish/monitoring PR #6** — keep `iss` royal-waterfall, point JWKS at buoy-fish, with a comment documenting the split).

1. **New config**, following the existing `*.default` + external-override pattern in `ConsoleConfig.java` (~line 1024, the "Cloudflare Access Shim" block) and `application.properties`:
   - `helium.cf.access.jwks.domain.default=buoy-fish.cloudflareaccess.com`
   - `helium.cf.access.jwks.domain:` (external override, empty default)
   - Add fields `cfAccessJwksDomainDefault` / `cfAccessJwksDomainExternal` + a `getCfAccessJwksDomain()` getter following the same precedence as `getCfAccessTeamDomain()`.
2. **`forTeam` takes the JWKS host separately:**
   ```java
   public static AccessJwtVerifier forTeam(String issuerDomain, String jwksDomain, String audience) throws Exception {
       String iss = "https://" + issuerDomain;
       URL jwksUrl = new URL("https://" + jwksDomain + "/cdn-cgi/access/certs");
       JWKSource<SecurityContext> remote = JWKSourceBuilder.create(jwksUrl).retrying(true).build();
       return new AccessJwtVerifier(remote, iss, audience);
   }
   ```
   Keep the issuer domain (`royal-waterfall`) as the source of `iss`; jwksDomain defaults to `buoy-fish`.
3. **Update the one caller** `service/AccessShimSetup.java:34`:
   `AccessJwtVerifier.forTeam(config.getCfAccessTeamDomain(), config.getCfAccessJwksDomain(), audience);`
4. **Leave `team.domain.default` = `royal-waterfall-9e8e…`** (it is the *issuer*, and it's correct).

## Tests (TDD — write the failing one first)

`src/test/java/eu/heliumiot/console/service/AccessJwtVerifierTest.java` already has `ISSUER = https://royal-waterfall-9e8e…` and a "wrong issuer is rejected" case via the injected-JWKSource constructor — keep those.
- The new behavior to pin is in `forTeam`: **the certs URL is built from the jwks-domain arg, independent of the issuer arg.** `JWKSourceBuilder.create(URL)` is lazy (no network at construction), but asserting the URL is awkward. Cleanest: extract a tiny `static URL certsUrl(String jwksDomain)` (or `String`) helper and unit-test `certsUrl("buoy-fish.cloudflareaccess.com")` == `https://buoy-fish.cloudflareaccess.com/cdn-cgi/access/certs`, decoupled from issuer. Write that test red against current code (where the URL comes from `iss`), then refactor green.
- Keep an end-to-end-ish test: token with `iss = royal-waterfall`, signed by an in-memory keyset injected as the JWKSource, verified by `new AccessJwtVerifier(localKeys, "https://royal-waterfall-9e8e…", AUDIENCE)` → passes; `iss = buoy-fish` or `some-other-team` → rejected.

## Cautions / repo state

- **Remotes:** `origin` = `git@github.com:buoy-fish/helium-chirpstack-community.git` (PR here). `community`/`upstream` = `disk91/helium-chirpstack-community` — **do NOT push there**; this team-domain config is buoy-specific and must not go upstream.
- **Untracked files present from other agents** (`TODO.md`, `docs/HPR_RECONCILIATION_PLAN.md`, `docs/drift-matrix.md`, `docs/join-captures/`, `lorawan-analyzer/`, `scripts/`). Don't sweep them into your commit; branch + `git add` only the auth files. Consider a worktree to isolate.
- **`aud`:** the console default `helium.cf.access.audience.default=` is **empty**. The live token's `aud` is `["7cacba06…c44b"]` (an array). Decide whether to pin audience (Grafana skips aud because Nimbus/Grafana compare scalar-vs-array — Nimbus' `DefaultJWTClaimsVerifier` *does* handle aud, but confirm array handling). Out of scope for the JWKS/iss fix but worth a glance while you're in here.
- **Stale docs to fix while here:** `docs/HANDOFF.md` (tools repo) and worktrees document the JWKS at the dead `royal-waterfall…/certs` — correct them. `sign.buoy.fish/docs/adr/0003` already *notes* the stale-ref discrepancy.

## Suggested skills

- `tdd` — red-green-refactor for the `forTeam` / `certsUrl` change (one failing test first).
- `verify` — exercise the shim end-to-end once tier-3 verification is enabled (replay a live token, expect a minted ChirpStack session, not a 401).
- `code-review` — before the PR.

## Cross-references (don't duplicate — read these)

- Memory `access-jwks-host-differs-from-iss` (in this Claude project's memory dir) — the canonical statement of the gotcha + which systems are affected.
- Monitoring precedent: **buoy-fish/monitoring PR #6** (diff + reasoning); already hotfixed live on the monitoring box.
- ADRs: `sign.buoy.fish/docs/adr/0002` (Access identity consumption), `0003` (accounting/console Access shim, match-only, deferred tier-3).
