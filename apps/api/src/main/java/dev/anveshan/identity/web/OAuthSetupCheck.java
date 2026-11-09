package dev.anveshan.identity.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/** Warns (without printing any value) when an OAuth client id was not provided via env vars. */
@Component
class OAuthSetupCheck {

	private static final Logger log = LoggerFactory.getLogger(OAuthSetupCheck.class);

	private final ClientRegistrationRepository registrations;

	OAuthSetupCheck(ClientRegistrationRepository registrations) {
		this.registrations = registrations;
	}

	@EventListener(ApplicationReadyEvent.class)
	void warnIfMissing() {
		for (String id : new String[] { "google", "github" }) {
			var reg = registrations.findByRegistrationId(id);
			if (reg == null || "not-configured".equals(reg.getClientId())) {
				log.warn("OAuth login '{}' is not configured: set {}_CLIENT_ID and {}_CLIENT_SECRET", id,
						id.toUpperCase(), id.toUpperCase());
			}
		}
	}

}
