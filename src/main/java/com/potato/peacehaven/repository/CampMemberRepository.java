package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.CampMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CampMemberRepository extends JpaRepository<CampMember, Long> {

    List<CampMember> findAllByOrderBySortOrderAsc();
}
