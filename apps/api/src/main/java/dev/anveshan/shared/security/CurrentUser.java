package dev.anveshan.shared.security;

import java.util.UUID;

/** The signed-in user for the current request (implemented by the identity module). Modules use this, never the session directly. */
public interface CurrentUser {

	/** @throws org.springframework.security.authentication.AuthenticationCredentialsNotFoundException if nobody is signed in */
	UUID id();

}
