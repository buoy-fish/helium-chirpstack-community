package eu.heliumiot.console.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mints a ChirpStack user-login JWT the way ChirpStack 4.x does, so the
 * walk-in (which has no password to run InternalService/Login) can still
 * hand the SPA a working chirpstackBearer.
 *
 * Format pinned against chirpstack v4.11.0 source (AuthClaim::new_for_user):
 * HS256 over {aud:"chirpstack", iss:"chirpstack", sub:<user-uuid>,
 * typ:"user", exp}, signed with the RAW api.secret bytes
 * (conf.api.secret.as_ref()). Verified live: a token of this shape returns
 * the user's profile from InternalService/Profile (grpc-status 0).
 */
public class ChirpstackBearerMinterTest {

    // 44-char base64 like chirpstack's `openssl rand -base64 32` secret
    static final String SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ=";

    Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
    }

    String rawPayloadJson(String token) {
        String[] parts = token.split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    @Test
    void mintsAChirpstackUserTokenWithTheExactClaims() {
        Date exp = new Date(System.currentTimeMillis() + 86_400_000L);
        String token = ChirpstackBearerMinter.mint("b901b059-96ec-4dd3-a213-2d7b319f0f8b", exp, SECRET);

        Claims c = parse(token);
        assertEquals("chirpstack", c.getIssuer());
        assertEquals("b901b059-96ec-4dd3-a213-2d7b319f0f8b", c.getSubject());
        assertEquals("user", c.get("typ"));
        assertEquals(exp.getTime() / 1000, c.getExpiration().getTime() / 1000);
    }

    @Test
    void audIsAStringNotAnArray() {
        // chirpstack's AuthClaim is `aud: String`; jjwt's audience builder
        // emits an array, which would fail chirpstack's deserialization.
        String token = ChirpstackBearerMinter.mint("uuid", new Date(System.currentTimeMillis() + 60_000L), SECRET);
        String json = rawPayloadJson(token);
        assertTrue(json.contains("\"aud\":\"chirpstack\""),
                "aud must be the string \"chirpstack\", payload was: " + json);
        assertFalse(json.contains("\"aud\":["), "aud must not be an array: " + json);
    }

    @Test
    void signatureUsesRawSecretBytes_aWrongSecretFails() {
        String token = ChirpstackBearerMinter.mint("uuid", new Date(System.currentTimeMillis() + 60_000L), SECRET);
        assertThrows(Exception.class, () ->
                Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor("a-completely-different-secret-key-of-len".getBytes(StandardCharsets.UTF_8)))
                        .build().parseSignedClaims(token));
    }
}
