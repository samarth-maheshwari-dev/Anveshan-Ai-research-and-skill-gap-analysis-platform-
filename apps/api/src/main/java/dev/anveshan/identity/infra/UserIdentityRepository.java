package dev.anveshan.identity.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.anveshan.identity.domain.AuthProvider;
import dev.anveshan.identity.domain.UserIdentity;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

	Optional<UserIdentity> findByProviderAndProviderSubject(AuthProvider provider, String providerSubject);

	List<UserIdentity> findByUserIdOrderByCreatedAt(UUID userId);

}
