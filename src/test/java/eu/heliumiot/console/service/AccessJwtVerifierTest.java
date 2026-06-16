package eu.heliumiot.console.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Access Shim's cryptographic core (ADR-0002, buoy-services repo):
 * Cloudflare Access has authenticated the visitor at the edge and injected
 * Cf-Access-Jwt-Assertion. The verifier's contract is to accept exactly the
 * tokens our Access application mints — right signature, right issuer,
 * right audience, unexpired — and surface the verified email; everything
 * else is rejected. The origin is tunnel-only, but we verify anyway:
 * defense in depth costs one RS256 check.
 */
public class AccessJwtVerifierTest {

    static final String ISSUER = "https://royal-waterfall-9e8e.cloudflareaccess.com";
    static final String AUDIENCE = "db81f6eda2501216aaaabbbbccccdddd0000111122223333aaaabbbbcccc0000";

    static RSAKey teamKey;       // stands in for the Cloudflare team key
    static RSAKey strangerKey;   // a valid RSA key Access never used
    static AccessJwtVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        teamKey = new RSAKeyGenerator(2048).keyID("team-key-1").generate();
        strangerKey = new RSAKeyGenerator(2048).keyID("stranger").generate();
        verifier = new AccessJwtVerifier(
                new ImmutableJWKSet<>(new JWKSet(teamKey.toPublicJWK())),
                ISSUER,
                AUDIENCE);
    }

    static String token(RSAKey key, String iss, String aud, String email, Date exp) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(iss)
                .audience(aud)
                .subject("user-uuid")
                .expirationTime(exp)
                .issueTime(new Date());
        if (email != null) claims.claim("email", email);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(key.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    static Date in(long minutes) {
        return new Date(System.currentTimeMillis() + minutes * 60_000L);
    }

    @Test
    void validTokenYieldsItsEmail() throws Exception {
        String t = token(teamKey, ISSUER, AUDIENCE, "jameson@buoy.fish", in(5));
        assertEquals("jameson@buoy.fish", verifier.verifiedEmail(t));
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        // a Member's perfectly valid JWT for a DIFFERENT Access app
        String t = token(teamKey, ISSUER, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "jameson@buoy.fish", in(5));
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail(t));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String t = token(teamKey, "https://some-other-team.cloudflareaccess.com", AUDIENCE, "jameson@buoy.fish", in(5));
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail(t));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String t = token(teamKey, ISSUER, AUDIENCE, "jameson@buoy.fish", in(-5));
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail(t));
    }

    @Test
    void signatureFromUnknownKeyIsRejected() throws Exception {
        String t = token(strangerKey, ISSUER, AUDIENCE, "jameson@buoy.fish", in(5));
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail(t));
    }

    @Test
    void missingEmailClaimIsRejected() throws Exception {
        // service tokens authenticate without an email — the Shim must not
        // mint a console session for them
        String t = token(teamKey, ISSUER, AUDIENCE, null, in(5));
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail(t));
    }

    @Test
    void garbageIsRejected() {
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail("not-a-jwt"));
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail(""));
        assertThrows(AccessJwtVerifier.InvalidAccessJwt.class, () -> verifier.verifiedEmail(null));
    }

    @Test
    void certsUrlFollowsJwksDomainNotIssuer() {
        // The certs URL must be built from the JWKS domain, independently of
        // the issuer: during a Cloudflare team-domain migration the token iss
        // can be one label while the signing keys are served only at another
        // (the other label's /certs returns 404). This pins that decoupling.
        assertEquals(
                "https://buoy-fish.cloudflareaccess.com/cdn-cgi/access/certs",
                AccessJwtVerifier.certsUrl("buoy-fish.cloudflareaccess.com"));
    }
}
