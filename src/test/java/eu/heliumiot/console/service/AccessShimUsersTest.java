package eu.heliumiot.console.service;

import eu.heliumiot.console.jpa.db.HeliumUser;
import eu.heliumiot.console.jpa.db.User;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Production binding of the Shim's user store. Console is MATCH-ONLY
 * (decided 2026-06-12): a console account is a billing entity — ChirpStack
 * tenant with DC-burn accountability — so accounts are created only through
 * the deliberate signup flow (which lives behind the Access wall), never
 * auto-provisioned from an edge header.
 */
public class AccessShimUsersTest {

    UserCacheService cache;
    UserService userService;
    AccessShimUsers users;

    @BeforeEach
    void setup() {
        cache = mock(UserCacheService.class);
        userService = mock(UserService.class);
        users = new AccessShimUsers(cache, userService);
    }

    UserCacheService.UserCacheElement element(String userid, boolean active, boolean admin) {
        UserCacheService.UserCacheElement e = cache.new UserCacheElement();
        e.user = new User();
        e.user.setActive(active);
        e.user.setAdmin(admin);
        e.heliumUser = new HeliumUser();
        e.heliumUser.setUserid(userid);
        return e;
    }

    @Test
    void activeUserResolvesWithKeyAndAdminFlag() {
        UserCacheService.UserCacheElement e = element("uuid-1", true, true);
        when(cache.getUserByUsername("jameson@buoy.fish")).thenReturn(e);
        when(userService.generateKeyForUser(e.heliumUser))
                .thenReturn(Keys.hmacShaKeyFor(new byte[64]));

        AccessShimService.ConsoleIdentity id = users.findActiveByEmail("jameson@buoy.fish");

        assertNotNull(id);
        assertEquals("uuid-1", id.userid());
        assertTrue(id.admin());
        assertNotNull(id.signingKey());
    }

    @Test
    void unknownEmailResolvesToNull() {
        when(cache.getUserByUsername("ghost@buoy.fish")).thenReturn(null);
        assertNull(users.findActiveByEmail("ghost@buoy.fish"));
    }

    @Test
    void inactiveUserResolvesToNull() {
        when(cache.getUserByUsername("gone@buoy.fish")).thenReturn(element("uuid-2", false, false));
        assertNull(users.findActiveByEmail("gone@buoy.fish"));
    }

    @Test
    void provisioningIsRefused_consoleIsMatchOnly() {
        assertThrows(AccessShimService.NoConsoleAccount.class,
                () -> users.provisionFromEmail("new@buoy.fish"));
        verifyNoInteractions(userService);
    }
}
