package eu.heliumiot.console.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Production user store for the Access Shim — MATCH-ONLY (2026-06-12):
 * a console account is a billing entity (ChirpStack tenant, DC-burn
 * accountability), so accounts come only from the deliberate signup flow,
 * which itself lives behind the Access Members wall. The Shim signs in
 * existing users; it never creates one.
 */
@Component
public class AccessShimUsers implements AccessShimService.ConsoleUsers {

    private final UserCacheService userCacheService;
    private final UserService userService;

    @Autowired
    public AccessShimUsers(UserCacheService userCacheService, UserService userService) {
        this.userCacheService = userCacheService;
        this.userService = userService;
    }

    @Override
    public AccessShimService.ConsoleIdentity findActiveByEmail(String email) {
        UserCacheService.UserCacheElement u = userCacheService.getUserByUsername(email);
        if (u == null || u.user == null || !u.user.isActive() || u.heliumUser == null) {
            return null;
        }
        return new AccessShimService.ConsoleIdentity(
                u.heliumUser.getUserid(),
                userService.generateKeyForUser(u.heliumUser),
                u.user.isAdmin());
    }

    @Override
    public AccessShimService.ConsoleIdentity provisionFromEmail(String email)
            throws AccessShimService.NoConsoleAccount {
        throw new AccessShimService.NoConsoleAccount(
                "no console account for " + email
                + " — accounts are created via the signup flow (match-only Shim)");
    }
}
