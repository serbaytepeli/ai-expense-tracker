package com.aiexpense.auth.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Issues HS256 access tokens with {@code sub} (user ID) and {@code sid} (session ID). */
@Component
class AccessTokens {

    static final String SESSION_CLAIM = "sid";

    record Issued(String token, long expiresInSeconds) {
        @Override
        public String toString() {
            return "Issued[token=***, expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    AccessTokens(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    Issued issue(UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .claim(SESSION_CLAIM, sessionId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Issued(token, properties.accessTokenTtl().toSeconds());
    }
}
