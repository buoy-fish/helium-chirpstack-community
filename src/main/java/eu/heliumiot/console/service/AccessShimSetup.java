package eu.heliumiot.console.service;

import eu.heliumiot.console.ConsoleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Access Shim from configuration. When no audience is configured
 * the bean still exists but is built around a sentinel audience no real
 * token carries — every exchange is rejected, i.e. the Shim is disabled
 * without any conditional-bean machinery.
 */
@Configuration
public class AccessShimSetup {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private static final String DISABLED_SENTINEL = "access-shim-disabled";

    @Bean
    public AccessShimService accessShimService(ConsoleConfig config, AccessShimUsers users)
            throws Exception {
        String audience = config.getCfAccessAudience();
        if (audience == null || audience.isBlank()) {
            log.info("Access Shim disabled — helium.cf.access.audience is not set");
            audience = DISABLED_SENTINEL;
        } else {
            log.info("Access Shim enabled for team {} (jwks {}, aud {}…)",
                    config.getCfAccessTeamDomain(), config.getCfAccessJwksDomain(),
                    audience.substring(0, Math.min(8, audience.length())));
        }
        AccessJwtVerifier verifier =
                AccessJwtVerifier.forTeam(config.getCfAccessTeamDomain(),
                        config.getCfAccessJwksDomain(), audience);
        if (config.getChirpstackApiSecret() == null || config.getChirpstackApiSecret().isBlank()) {
            log.warn("chirpstack.api.secret not set — walk-in sessions will carry no "
                    + "chirpstack bearer and device/gateway views will prompt for login");
        }
        return new AccessShimService(verifier, users, config.getCfAccessSessionMs(),
                config.getChirpstackApiSecret());
    }
}
