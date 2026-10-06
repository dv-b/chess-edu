package com.chessedu.platform.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Fallback {@link EmailService} when there is no {@link org.springframework.mail.javamail.JavaMailSender}
 * on the classpath — typical for tests that exclude {@code MailSenderAutoConfiguration}
 * and for local debugging where an SMTP host isn't configured.
 *
 * <p>Logs the fact that a send was skipped so the operator can notice in dev.
 * Not a {@code @Component} — wired via {@link EmailConfig}.
 */
public class NoopEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(NoopEmailService.class);

    @Override
    public void sendInvitation(InvitationEmail email, Locale locale) {
        log.info("[noop-email] would send invitation to={} role={} school={} url={}",
                email.to(), email.role(), email.schoolName(), email.acceptUrl());
    }
}
