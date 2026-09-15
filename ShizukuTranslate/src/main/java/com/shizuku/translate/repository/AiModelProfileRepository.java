package com.shizuku.translate.repository;

import com.shizuku.translate.entity.AiModelProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelProfileRepository extends JpaRepository<AiModelProfile, Long> {

    /**
     * {@code personalModelApiKey} is a LAZY association that callers read after the
     * repository transaction has already closed (e.g. UserService.effectiveApiKey, which is
     * reached from the non-transactional streaming path). Without an entity graph those
     * reads throw LazyInitializationException once open-in-view is off - and on the async
     * streaming threads even while it is on, because those threads have no request-scoped
     * EntityManager. Fetching it eagerly makes the detached entity safe to read.
     */
    @EntityGraph(attributePaths = "personalModelApiKey")
    List<AiModelProfile> findByUserIdOrderByCreatedAtAsc(Long userId);

    @EntityGraph(attributePaths = "personalModelApiKey")
    Optional<AiModelProfile> findByIdAndUserId(Long id, Long userId);
}
