package de.sgart;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Regression guard for the code-review D1 fix (Story 4.1): outside the "dev" profile, a missing
 * {@code SGART_INVITE_EMAIL_HMAC_SECRET} must fail context startup rather than silently falling
 * back to the committed dev-only default — a prod deploy without the real secret must never boot.
 */
class InviteEmailHmacSecretProfileGuardTest {

    @Test
    void contextFailsToStartOutsideDevProfileWithoutAnExplicitInviteEmailHmacSecret() {
        SpringApplication application = new SpringApplication(SgartApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("prod");

        assertThatThrownBy(application::run).isNotNull();
    }

    @Test
    void contextStartsUnderTheDevProfileWithoutAnExplicitInviteEmailHmacSecret() {
        SpringApplication application = new SpringApplication(SgartApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("dev");

        ConfigurableApplicationContext context = application.run();
        context.close();
    }
}
