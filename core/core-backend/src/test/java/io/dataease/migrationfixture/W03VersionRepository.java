package io.dataease.migrationfixture;

import io.dataease.dao.auto.repo.DeStandaloneVersionRepository;

/** Narrow fixture scan, retaining the production repository methods and real JPA transactions. */
public interface W03VersionRepository extends DeStandaloneVersionRepository {
}
