package dev.anveshan.identity.api;

import java.util.UUID;

/** The signed-in user for the current request. Other modules use this, never the session directly. */
public interface CurrentUser {

	/** @throws org.springframework.security.authentication.AuthenticationCredentialsNotFoundException if nobody is signed in */
	UUID id();

}
