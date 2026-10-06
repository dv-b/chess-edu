package com.chessedu.platform.email;

import com.chessedu.platform.common.config.MailProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * {@link EmailService} backed by Spring's {@link JavaMailSender}. SMTP host
 * is configured via {@code spring.mail.*} (Mailpit in dev on port 2525).
 *
 * <p>All emails are sent as UTF-8 HTML with a plain-text alternative so
 * clients like Outlook degrade gracefully.
 */
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties mailProps;
    private final MessageSource messages;

    public SmtpEmailService(JavaMailSender mailSender,
                            MailProperties mailProps,
                            MessageSource messages) {
        this.mailSender = mailSender;
        this.mailProps = mailProps;
        this.messages = messages;
    }

    @Override
    public void sendInvitation(InvitationEmail payload, Locale locale) {
        Locale safe = locale == null ? Locale.ENGLISH : locale;

        String subject = messages.getMessage(
                "email.invitation.subject",
                new Object[]{payload.schoolName() != null ? payload.schoolName() : messages.getMessage("app.name", null, safe)},
                safe
        );

        String greeting = messages.getMessage("email.invitation.greeting",
                new Object[]{payload.recipientName()}, safe);
        String intro = messages.getMessage("email.invitation.intro",
                new Object[]{
                        payload.inviterName() != null ? payload.inviterName() : messages.getMessage("app.name", null, safe),
                        payload.schoolName() != null ? payload.schoolName() : messages.getMessage("app.name", null, safe),
                        payload.role()
                }, safe);
        String cta = messages.getMessage("email.invitation.cta", null, safe);
        String expiresLabel = messages.getMessage("email.invitation.expires",
                new Object[]{payload.expiresAtLocal()}, safe);
        String footer = messages.getMessage("email.invitation.footer", null, safe);

        String text = greeting + "\n\n" + intro + "\n\n" + payload.acceptUrl() + "\n\n" + expiresLabel + "\n\n" + footer;
        String html = renderHtml(greeting, intro, cta, payload.acceptUrl(), expiresLabel, footer);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(mailProps.fromAddress(), mailProps.fromName(), StandardCharsets.UTF_8.name()));
            helper.setTo(payload.to());
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.send(message);
            log.info("Sent invitation email to {} (locale={})", payload.to(), safe);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            // We intentionally swallow here — caller (InvitationService) has
            // already committed the invitation row, and the operator can
            // resend from the UI. Emitting ERROR so it's visible in logs.
            log.error("Failed to send invitation email to {}", payload.to(), ex);
        }
    }

    private static String renderHtml(String greeting, String intro, String cta, String url,
                                     String expiresLabel, String footer) {
        return "<!doctype html><html><body style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;line-height:1.55;color:#1a1a1a;background:#f7f5f1;padding:32px 0\">"
                + "<table role=\"presentation\" width=\"520\" align=\"center\" style=\"background:#fff;border:1px solid #e8e4dd;border-radius:12px;padding:32px\">"
                + "<tr><td>"
                + "<p style=\"margin:0 0 16px;font-size:15px;\">" + escape(greeting) + "</p>"
                + "<p style=\"margin:0 0 24px;font-size:15px;\">" + escape(intro) + "</p>"
                + "<p style=\"margin:0 0 28px;\">"
                + "<a href=\"" + escape(url) + "\" style=\"display:inline-block;padding:12px 18px;border-radius:8px;background:#6f4e1a;color:#fff;text-decoration:none;font-weight:500;font-size:14px;\">" + escape(cta) + "</a>"
                + "</p>"
                + "<p style=\"margin:0 0 24px;font-size:13px;color:#6b6b6b;\">" + escape(expiresLabel) + "</p>"
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:24px 0\" />"
                + "<p style=\"margin:0;font-size:12px;color:#8a8a8a;\">" + escape(footer) + "</p>"
                + "</td></tr></table></body></html>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
