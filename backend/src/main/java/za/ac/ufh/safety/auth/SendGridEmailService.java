package za.ac.ufh.safety.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends the two-step-login code through SendGrid's Mail Send API over plain
 * HTTP (java.net.http.HttpClient, built into Java 11+) rather than adding
 * SendGrid's SDK as a Maven dependency - one JSON POST is all this needs.
 *
 * MAIL_API_KEY / MAIL_FROM unset - a teammate running the backend locally
 * without a SendGrid account - is treated as "email is not configured here,"
 * not an error: the code is written to the server's own log instead of
 * emailed, so the team can keep testing the login flow locally without every
 * developer needing a SendGrid account. That log line is server-side only;
 * nothing in the HTTP response to the browser ever contains the code,
 * whether or not email is configured. Production (Railway) has both set and
 * always sends a real email - see README-BACKEND.md for what to set there.
 */
@Service
public class SendGridEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailService.class);
    private static final URI SENDGRID_ENDPOINT = URI.create("https://api.sendgrid.com/v3/mail/send");

    private final String apiKey;
    private final String fromEmail;
    private final String fromName;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public SendGridEmailService(
        @Value("${app.mail.api-key:}") String apiKey,
        @Value("${app.mail.from:}") String fromEmail,
        @Value("${app.mail.from-name:UFH Safety Platform}") String fromName,
        ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.objectMapper = objectMapper;
    }

    @Override
    public void sendLoginCode(String toEmail, String code) {
        String subject = "Your UFH Safety sign-in code";
        String body = "Your sign-in code is " + code + ". It expires in 5 minutes.\n\n"
            + "If you did not just try to sign in, you can ignore this email - "
            + "nobody can get into your account with your password alone.";

        if (apiKey.isBlank() || fromEmail.isBlank()) {
            log.warn(
                "MAIL_API_KEY or MAIL_FROM is not set, so no email was sent. "
                    + "Login code for {} (dev-only - this line must never appear in production): {}",
                toEmail, code);
            return;
        }

        try {
            // reply_to matching the verified sender is a small, genuine
            // deliverability signal (a missing reply-to on a transactional
            // email is itself something spam filters weigh against a
            // message) - it does not fix the underlying cause of this
            // landing in spam, which is that MAIL_FROM has no Domain
            // Authentication (SPF/DKIM alignment) in SendGrid. That needs a
            // domain we actually control the DNS for; Single Sender
            // Verification (what MAIL_FROM uses today) proves ownership of
            // one address, nothing more. See README-BACKEND.md.
            //
            // tracking_settings disabled: SendGrid's account-level default is
            // click/open tracking on, which rewrites any link in the body
            // through an unbranded sendgrid.net redirect domain and embeds an
            // invisible open-tracking pixel - both are themselves spam
            // signals, and neither is needed for a one-time code or a
            // single-use reset link nobody is measuring engagement on.
            Map<String, Object> payload = Map.of(
                "personalizations", List.of(Map.of("to", List.of(Map.of("email", toEmail)))),
                "from", Map.of("email", fromEmail, "name", fromName),
                "reply_to", Map.of("email", fromEmail, "name", fromName),
                "subject", subject,
                "content", List.of(Map.of("type", "text/plain", "value", body)),
                "tracking_settings", Map.of(
                    "click_tracking", Map.of("enable", false),
                    "open_tracking", Map.of("enable", false)
                )
            );

            HttpRequest request = HttpRequest.newBuilder(SENDGRID_ENDPOINT)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("SendGrid rejected the login-code email: {} {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            // A student whose email happens to be down should not be told
            // that in the request-code response (it already ran after the
            // password check succeeded, so the account is real either way) -
            // log it and let them retry with "Resend code" instead.
            log.error("Could not reach the email provider to send the login code", e);
        }
    }
}
