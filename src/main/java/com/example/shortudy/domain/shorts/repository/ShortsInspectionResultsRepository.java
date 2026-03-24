package com.example.shortudy.domain.shorts.repository;

import com.example.shortudy.domain.shorts.entity.ShortsInspectionResults;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortsInspectionResultsRepository extends JpaRepository<ShortsInspectionResults, Long> {

    Optional<ShortsInspectionResults> findByShortsId(Long shortsId);

    // ✅ N+1 방지용
    List<ShortsInspectionResults> findByShortsIdIn(Collection<Long> shortsIds);

    void deleteByShortsId(Long shortsId);
}
