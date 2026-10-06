package com.chessedu.platform.identity.invitation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Invitation knobs. {@code ttl} controls how long a fresh invitation remains
 * acceptable. Default is 7 days — enough for someone to find the email on
 * Monday after it was sent on a Friday.
 */
@ConfigurationProperties(prefix = "chessedu.invitation")
public record InvitationProperties(Duration ttl) {

    public Duration ttl() {
        return ttl == null ? Duration.ofDays(7) : ttl;
    }
}
