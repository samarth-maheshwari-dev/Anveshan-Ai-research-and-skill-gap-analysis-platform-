package dev.anveshan.shared.logging;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;

/** Stack traces (incl. exception messages and causes) are masked too: {@code %maskedEx}. */
public class MaskingThrowableConverter extends ExtendedThrowableProxyConverter {

	@Override
	protected String throwableProxyToString(IThrowableProxy tp) {
		String s = super.throwableProxyToString(tp);
		return s.isEmpty() ? s : System.lineSeparator() + SecretMasker.mask(s.stripLeading());
	}

}
