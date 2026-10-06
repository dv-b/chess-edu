package com.chessedu.platform.email;

import com.chessedu.platform.common.config.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Registers the active {@link EmailService} implementation.
 *
 * <p>We deliberately avoid {@link org.springframework.boot.autoconfigure.condition.ConditionalOnBean}
 * here: that annotation is only reliable inside {@code @AutoConfiguration} classes
 * because it's evaluated at bean-definition time, which can race
 * {@code MailSenderAutoConfiguration} in user {@code @Configuration} classes and
 * silently fall back to the noop implementation even when SMTP is configured.
 *
 * <p>Instead we inject an {@link ObjectProvider} and resolve it at bean-method-invocation
 * time — by then auto-configuration has finished, so the decision is deterministic.
 */
@Configuration
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    public EmailService emailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                                     MailProperties mailProps,
                                     MessageSource messages) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender != null) {
            log.info("EmailService: SMTP enabled (from='{}' <{}>)", mailProps.fromName(), mailProps.fromAddress());
            return new SmtpEmailService(sender, mailProps, messages);
        }
        log.warn("EmailService: no JavaMailSender on the context — falling back to NoopEmailService. "
                + "Emails will be logged, not sent.");
        return new NoopEmailService();
    }
}
