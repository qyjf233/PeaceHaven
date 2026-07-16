package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.PersonaStability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonaStabilityRepository extends JpaRepository<PersonaStability, Long> {
}
