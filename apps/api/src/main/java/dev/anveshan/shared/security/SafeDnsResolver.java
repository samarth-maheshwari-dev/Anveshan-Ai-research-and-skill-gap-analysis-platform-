package dev.anveshan.shared.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * Resolves a host and refuses it if <b>any</b> resolved address is blocked. The connection is made
 * to exactly the addresses returned here, so a DNS answer can't change between check and connect
 * (DNS-rebinding defence, TECHSPEC TS-D4).
 */
public final class SafeDnsResolver implements DnsResolver {

	private final DnsResolver delegate;
	private final OutboundUrlGuard guard;

	public SafeDnsResolver(OutboundUrlGuard guard) {
		this(SystemDefaultDnsResolver.INSTANCE, guard);
	}

	/** Tests inject a resolver that maps made-up names to chosen IPs. */
	public SafeDnsResolver(DnsResolver delegate, OutboundUrlGuard guard) {
		this.delegate = delegate;
		this.guard = guard;
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress[] all = delegate.resolve(host);
		if (all == null || all.length == 0) {
			throw new UnknownHostException("no address");
		}
		for (InetAddress a : all) {
			if (guard.blocks(a)) {
				throw new BlockedAddressException();
			}
		}
		return all;
	}

	@Override
	public String resolveCanonicalHostname(String host) throws UnknownHostException {
		return delegate.resolveCanonicalHostname(host);
	}

	/** Thrown from DNS resolution so HttpClient aborts before opening a socket. */
	public static final class BlockedAddressException extends UnknownHostException {

		BlockedAddressException() {
			super("blocked: resolves to a private or reserved address");
		}

	}

}
