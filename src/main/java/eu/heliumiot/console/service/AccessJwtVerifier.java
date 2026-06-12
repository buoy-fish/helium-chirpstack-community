package eu.heliumiot.console.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URL;
import java.util.Set;

/**
 * Cryptographic core of the Cloudflare Access Shim (ADR-0002 in the
 * buoy-services repo): validates the Cf-Access-Jwt-Assertion header that
 * Access injects at the edge and surfaces the verified visitor email.
 *
 * Accepts exactly the tokens our Access application mints — RS256 signature
 * against the team's JWKS, pinned issuer, pinned audience, unexpired, and
 * carrying an email claim (service tokens authenticate without one and must
 * NOT become console sessions). Everything else throws InvalidAccessJwt.
 *
 * The origin is tunnel-only (ADR-0001), so a forged header can't normally
 * reach us — this check is defense in depth, priced at one RS256 verify.
 */
public class AccessJwtVerifier {

    public static class InvalidAccessJwt extends Exception {
        public InvalidAccessJwt(String message, Throwable cause) {
            super(message, cause);
        }
        public InvalidAccessJwt(String message) {
            super(message);
        }
    }

    private final DefaultJWTProcessor<SecurityContext> processor;

    public AccessJwtVerifier(JWKSource<SecurityContext> keys, String issuer, String audience) {
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        this.processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                audience,
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("exp", "email")));
    }

    /**
     * Production wiring: remote JWKS with Nimbus' built-in caching and
     * rate-limiting, so Cloudflare key rotation is handled transparently.
     */
    public static AccessJwtVerifier forTeam(String teamDomain, String audience) throws Exception {
        String iss = "https://" + teamDomain;
        JWKSource<SecurityContext> remote = JWKSourceBuilder
                .create(new URL(iss + "/cdn-cgi/access/certs"))
                .retrying(true)
                .build();
        return new AccessJwtVerifier(remote, iss, audience);
    }

    /**
     * @return the verified email claim of a genuine, unexpired Access JWT
     *         for our application.
     * @throws InvalidAccessJwt for anything else — bad signature, wrong
     *         issuer or audience, expired, missing email, garbage.
     */
    public String verifiedEmail(String token) throws InvalidAccessJwt {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessJwt("no Access JWT presented");
        }
        try {
            JWTClaimsSet claims = processor.process(token, null);
            String email = claims.getStringClaim("email");
            if (email == null || email.isBlank()) {
                throw new InvalidAccessJwt("Access JWT carries no email (service token?)");
            }
            return email.toLowerCase();
        } catch (InvalidAccessJwt e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidAccessJwt("Access JWT rejected: " + e.getMessage(), e);
        }
    }
}
