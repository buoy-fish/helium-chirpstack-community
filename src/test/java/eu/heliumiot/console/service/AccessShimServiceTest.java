package eu.heliumiot.console.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Key;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The Shim's orchestration (ADR-0002): a verified Access email becomes a
 * console session. Match an existing console user and mint their bearer;
 * provision only when there's no match. Crypto (AccessJwtVerifier) and the
 * token shape (ConsoleBearerMinter) are pinned elsewhere — here we pin the
 * decision flow, with the user store and verifier mocked.
 */
public class AccessShimServiceTest {

    static final long TTL = 3_600_000L;
    static final Key MEMBER_KEY = Keys.hmacShaKeyFor(new byte[64]);

    AccessJwtVerifier verifier;
    AccessShimService.ConsoleUsers users;
    AccessShimService shim;

    @BeforeEach
    void setup() {
        verifier = mock(AccessJwtVerifier.class);
        users = mock(AccessShimService.ConsoleUsers.class);
        shim = new AccessShimService(verifier, users, TTL);
    }

    List<String> rolesOf(String bearer) {
        Claims c = Jwts.parser().verifyWith((javax.crypto.SecretKey) MEMBER_KEY)
                .build().parseSignedClaims(bearer).getPayload();
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) c.get("roles");
        return roles;
    }

    String subjectOf(String bearer) {
        return Jwts.parser().verifyWith((javax.crypto.SecretKey) MEMBER_KEY)
                .build().parseSignedClaims(bearer).getPayload().getSubject();
    }

    @Test
    void existingMemberGetsABearerForTheirOwnId() throws Exception {
        when(verifier.verifiedEmail("access-jwt")).thenReturn("jameson@buoy.fish");
        when(users.findActiveByEmail("jameson@buoy.fish"))
                .thenReturn(new AccessShimService.ConsoleIdentity("uuid-jameson", MEMBER_KEY, false));

        AccessShimService.ExchangeResult r = shim.exchangeForConsoleBearer("access-jwt");

        assertEquals("uuid-jameson", subjectOf(r.consoleBearer()));
        assertEquals(List.of("ROLE_USER"), rolesOf(r.consoleBearer()));
        assertFalse(r.admin());
        verify(users, never()).provisionFromEmail(anyString());
    }

    @Test
    void adminIdentityCarriesTheAdminRole() throws Exception {
        when(verifier.verifiedEmail("access-jwt")).thenReturn("jameson@buoy.fish");
        when(users.findActiveByEmail("jameson@buoy.fish"))
                .thenReturn(new AccessShimService.ConsoleIdentity("uuid-jameson", MEMBER_KEY, true));

        AccessShimService.ExchangeResult r = shim.exchangeForConsoleBearer("access-jwt");
        assertEquals(List.of("ROLE_USER", "ROLE_ADMIN"), rolesOf(r.consoleBearer()));
        assertTrue(r.admin());
    }

    @Test
    void unknownEmailProvisionsThenMints() throws Exception {
        when(verifier.verifiedEmail("access-jwt")).thenReturn("newbie@buoy.fish");
        when(users.findActiveByEmail("newbie@buoy.fish")).thenReturn(null);
        when(users.provisionFromEmail("newbie@buoy.fish"))
                .thenReturn(new AccessShimService.ConsoleIdentity("uuid-new", MEMBER_KEY, false));

        AccessShimService.ExchangeResult r = shim.exchangeForConsoleBearer("access-jwt");

        assertEquals("uuid-new", subjectOf(r.consoleBearer()));
        verify(users).provisionFromEmail("newbie@buoy.fish");
    }

    @Test
    void anInvalidAccessJwtNeverTouchesTheUserStore() throws Exception {
        when(verifier.verifiedEmail("bad")).thenThrow(new AccessJwtVerifier.InvalidAccessJwt("nope"));

        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> shim.exchangeForConsoleBearer("bad"));
        verifyNoInteractions(users);
    }
}
