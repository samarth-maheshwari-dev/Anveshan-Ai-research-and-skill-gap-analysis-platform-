package dev.anveshan.shared.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Replaces Logback's %m / %msg / %message so every log message is masked. */
public class MaskingMessageConverter extends MessageConverter {

	@Override
	public String convert(ILoggingEvent event) {
		return SecretMasker.mask(super.convert(event));
	}

}
