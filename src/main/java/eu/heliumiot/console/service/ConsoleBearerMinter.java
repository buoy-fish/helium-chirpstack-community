package eu.heliumiot.console.service;

import io.jsonwebtoken.Jwts;

import java.security.Key;
import java.util.Date;
import java.util.List;

/**
 * Mints the console's own session token (the "consoleBearer", HS512) in the
 * one canonical shape JWTAuthorizationFilter expects: subject in both the
 * JWS header and the claims, a roles array, and an expiration, signed with
 * the caller's per-user key (see UserService.generateKeyForUser).
 *
 * Extracted from UserService.verifyUserLogin so the password-login path and
 * the Cloudflare Access Shim mint byte-identical tokens — neither can drift
 * from what the auth filter accepts.
 */
public final class ConsoleBearerMinter {

    private ConsoleBearerMinter() {}

    public static String mint(String userid, List<String> roles, Date expiration, Key signingKey) {
        io.jsonwebtoken.Claims claims = Jwts.claims()
                .subject(userid)
                .expiration(expiration)
                .add("roles", roles)
                .build();

        return Jwts.builder()
                .header().add("typ", "JWT")
                .add("sub", userid)
                .and()
                .claims().empty().add(claims)
                .and()
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }
}
