package eu.heliumiot.console.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.security.Key;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console bearer is minted in exactly one shape today, inline in
 * UserService.verifyUserLogin. The Access Shim must mint an IDENTICAL token
 * — same subject, roles, expiration, signing key — so the existing
 * JWTAuthorizationFilter accepts it unchanged. This pins that shape in a
 * pure seam both call sites share, so the two can never drift.
 */
public class ConsoleBearerMinterTest {

    /** A 64-byte HMAC key like generateKeyForUser produces. */
    static Key key(byte fill) {
        byte[] b = new byte[64];
        java.util.Arrays.fill(b, fill);
        return Keys.hmacShaKeyFor(b);
    }

    @Test
    void mintedTokenParsesBackWithItsClaims() {
        Key k = key((byte) 0x5a);
        Date exp = new Date(System.currentTimeMillis() + 3_600_000L);

        String token = ConsoleBearerMinter.mint("user-uuid-123", List.of("ROLE_USER", "ROLE_ADMIN"), exp, k);

        Claims claims = Jwts.parser().verifyWith((javax.crypto.SecretKey) k)
                .build().parseSignedClaims(token).getPayload();
        assertEquals("user-uuid-123", claims.getSubject());
        assertEquals(List.of("ROLE_USER", "ROLE_ADMIN"), claims.get("roles"));
        // second-resolution equality — JWT exp is unix seconds
        assertEquals(exp.getTime() / 1000, claims.getExpiration().getTime() / 1000);
    }

    @Test
    void subjectIsAlsoInTheHeader() {
        // JWTAuthorizationFilter reads `sub` from the JWS *header* to locate
        // the per-user key before it can verify the signature — so the
        // header sub must be present and match.
        Key k = key((byte) 0x33);
        String token = ConsoleBearerMinter.mint("abc", List.of("ROLE_USER"),
                new Date(System.currentTimeMillis() + 60_000L), k);
        String headerB64 = token.substring(0, token.indexOf('.'));
        String header = new String(java.util.Base64.getUrlDecoder().decode(headerB64));
        assertTrue(header.contains("\"sub\":\"abc\""), "header was: " + header);
    }

    @Test
    void aDifferentKeyDoesNotVerify() {
        String token = ConsoleBearerMinter.mint("u", List.of("ROLE_USER"),
                new Date(System.currentTimeMillis() + 60_000L), key((byte) 0x01));
        assertThrows(Exception.class, () ->
                Jwts.parser().verifyWith((javax.crypto.SecretKey) key((byte) 0x02))
                        .build().parseSignedClaims(token));
    }
}
