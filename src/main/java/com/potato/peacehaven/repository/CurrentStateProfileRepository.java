package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.CurrentStateProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CurrentStateProfileRepository extends JpaRepository<CurrentStateProfile, Long> {
}
