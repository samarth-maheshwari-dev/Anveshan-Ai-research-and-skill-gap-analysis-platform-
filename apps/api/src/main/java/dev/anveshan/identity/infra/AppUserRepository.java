package dev.anveshan.identity.infra;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.anveshan.identity.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	/** Live (not soft-deleted) account with a verified email, matched case-insensitively. */
	@Query("select u from AppUser u where lower(u.email) = lower(:email) and u.deletedAt is null and u.emailVerified = true")
	Optional<AppUser> findLiveVerifiedByEmail(@Param("email") String email);

	@Query("select u from AppUser u where u.id = :id and u.deletedAt is null")
	Optional<AppUser> findLive(@Param("id") UUID id);

}
