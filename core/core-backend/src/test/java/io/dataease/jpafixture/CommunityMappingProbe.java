package io.dataease.jpafixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test-only positive control for the unchanged community entity scan. */
@Entity
@Table(name = "w03_mapping_community")
public class CommunityMappingProbe {
    @Id
    private Long id;
}
