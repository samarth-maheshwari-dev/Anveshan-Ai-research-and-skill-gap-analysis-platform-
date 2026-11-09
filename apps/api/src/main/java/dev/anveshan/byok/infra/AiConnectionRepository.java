package dev.anveshan.byok.infra;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.anveshan.byok.domain.AiConnection;

/** Every finder is owner-scoped in SQL (RULES §0 rule 6): there is no "load by id, then check". */
public interface AiConnectionRepository extends JpaRepository<AiConnection, UUID> {

	Optional<AiConnection> findByIdAndOwnerId(UUID id, UUID ownerId);

	List<AiConnection> findByOwnerIdOrderByCreatedAt(UUID ownerId);

	List<AiConnection> findByOwnerIdAndIdIn(UUID ownerId, Collection<UUID> ids);

	long deleteByIdAndOwnerId(UUID id, UUID ownerId);

}
