package eu.heliumiot.console.service;

import java.security.Key;
import java.util.Date;
import java.util.List;

/**
 * Orchestrates the Cloudflare Access walk-in (ADR-0002): exchange the
 * Cf-Access-Jwt-Assertion header for the console's own session bearer.
 *
 * Flow: verify the Access JWT → take its email → match an active console
 * user → mint that user's bearer. Provision only when there is no match
 * (match-or-provision; accounting's Shim will be match-only). The crypto
 * (AccessJwtVerifier) and token shape (ConsoleBearerMinter) live in their
 * own pinned seams; this class is just the decision flow.
 *
 * The user store is abstracted behind {@link ConsoleUsers} so this logic is
 * unit-testable without Spring or a database; the production binding lives
 * in {@link AccessShimUsers}.
 */
public class AccessShimService {

    /** A resolved console user: the id the bearer is minted for, its
     *  per-user signing key, and whether it carries ROLE_ADMIN. */
    public record ConsoleIdentity(String userid, Key signingKey, boolean admin) {}

    /** The console user store, as the Shim needs it. */
    public interface ConsoleUsers {
        /** The active console user for this email, or null if none. */
        ConsoleIdentity findActiveByEmail(String email);
        /** Create a console user for a verified Access email and return it. */
        ConsoleIdentity provisionFromEmail(String email) throws Exception;
    }

    private final AccessJwtVerifier verifier;
    private final ConsoleUsers users;
    private final long ttlMs;

    public AccessShimService(AccessJwtVerifier verifier, ConsoleUsers users, long ttlMs) {
        this.verifier = verifier;
        this.users = users;
        this.ttlMs = ttlMs;
    }

    /**
     * @return a freshly minted console bearer for the Access-verified visitor
     * @throws AccessJwtVerifier.InvalidAccessJwt if the header is not a
     *         genuine Access JWT for this application
     * @throws Exception if provisioning a new user fails
     */
    public String exchangeForConsoleBearer(String accessJwt) throws Exception {
        String email = verifier.verifiedEmail(accessJwt);
        ConsoleIdentity id = users.findActiveByEmail(email);
        if (id == null) {
            id = users.provisionFromEmail(email);
        }
        List<String> roles = id.admin()
                ? List.of(UserService.ROLE_USER, UserService.ROLE_ADMIN)
                : List.of(UserService.ROLE_USER);
        return ConsoleBearerMinter.mint(
                id.userid(), roles, new Date(System.currentTimeMillis() + ttlMs), id.signingKey());
    }
}
