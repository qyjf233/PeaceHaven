package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.SceneProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SceneProfileRepository extends JpaRepository<SceneProfile, Long> {

    Optional<SceneProfile> findBySceneType(String sceneType);

    /** 查找样本数足够的场景画像 */
    List<SceneProfile> findBySampleCountGreaterThan(int minSamples);
}
