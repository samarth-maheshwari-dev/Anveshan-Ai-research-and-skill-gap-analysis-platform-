package dev.anveshan.identity.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.identity.app.AccountService;
import dev.anveshan.identity.infra.security.SessionUser;
import dev.anveshan.shared.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;

@RestController
class MeController {

	private final AccountService accounts;

	MeController(AccountService accounts) {
		this.accounts = accounts;
	}

	/** The signed-in user. A session whose account was deleted is ended and answered with 401. */
	@GetMapping("/api/v1/me")
	ResponseEntity<?> me(@AuthenticationPrincipal SessionUser user, HttpServletRequest request) {
		if (user != null) {
			var me = accounts.me(user.userId());
			if (me.isPresent()) {
				return ResponseEntity.ok(me.get());
			}
		}
		var session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ProblemResponses.of(HttpStatus.UNAUTHORIZED,
				"NOT_AUTHENTICATED", "Please sign in", "Your session has ended. Please sign in again."));
	}

}
