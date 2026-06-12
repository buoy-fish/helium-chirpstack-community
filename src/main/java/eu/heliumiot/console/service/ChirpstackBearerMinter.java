package eu.heliumiot.console.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Mints a ChirpStack user-login JWT without a password, so the Access Shim
 * can hand the console SPA a working chirpstackBearer (the second token the
 * fork needs, alongside the consoleBearer).
 *
 * Shape pinned to ChirpStack v4.11.0 (AuthClaim::new_for_user): HS256 over
 * {aud:"chirpstack", iss:"chirpstack", sub:<user-uuid>, typ:"user", exp},
 * signed with the RAW api.secret bytes (ChirpStack does
 * conf.api.secret.as_ref(), i.e. the secret string's UTF-8 bytes — NOT
 * base64-decoded). Claims are set individually so `aud` serializes as a
 * string; jjwt's audience builder would emit an array, which ChirpStack's
 * `aud: String` rejects. Verified live against InternalService/Profile.
 */
public final class ChirpstackBearerMinter {

    private ChirpstackBearerMinter() {}

    public static String mint(String userId, Date expiration, String apiSecret) {
        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .claim("aud", "chirpstack")
                .claim("iss", "chirpstack")
                .claim("sub", userId)
                .claim("typ", "user")
                .expiration(expiration)
                .signWith(Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
