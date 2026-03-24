package com.example.shortudy.domain.recommendation.service;

import com.example.shortudy.domain.comment.repository.CommentRepository;
import com.example.shortudy.domain.like.repository.ShortsLikeRepository;
import com.example.shortudy.domain.recommendation.dto.response.RecommendationResponse;
import com.example.shortudy.domain.shorts.dto.ShortsResponse;
import com.example.shortudy.domain.shorts.entity.Shorts;
import com.example.shortudy.domain.shorts.entity.ShortsStatus;
import com.example.shortudy.domain.shorts.entity.ShortsVisibility;
import com.example.shortudy.domain.shorts.repository.ShortsRepository;
import com.example.shortudy.global.error.BaseException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;
import jakarta.persistence.EntityManager;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.shortudy.global.error.ErrorCode.SHORTS_NOT_FOUND;

/**
 * 숏츠 추천 서비스
 * [알고리즘] 자카드 유사도 기반 키워드 매칭
 * [후보 선정 전략] 2단계 fallback
 * 1차: 같은 카테고리 숏츠 (최신순)
 * 2차: 랜덤 PUBLISHED 숏츠 (RAND)
 */
@Service
@Transactional(readOnly = true)
public class ShortsRecommendationService {

    private static final int MAX_CANDIDATES = 60;
    private static final int BATCH_SIZE = 60;

    private final ShortsRepository shortsRepository;
    private final EntityManager entityManager;
    private final CommentRepository commentRepository;
    private final ShortsLikeRepository shortsLikeRepository;

    public ShortsRecommendationService(
            ShortsRepository shortsRepository,
            EntityManager entityManager,
            CommentRepository commentRepository,
            ShortsLikeRepository shortsLikeRepository
    ) {
        this.shortsRepository = shortsRepository;
        this.entityManager = entityManager;
        this.commentRepository = commentRepository;
        this.shortsLikeRepository = shortsLikeRepository;
    }

    /**
     * 숏츠 추천 목록 조회
     * [처리 흐름]
     * 1. 기준 숏츠 조회 (keyword fetch join으로 N+1 방지)
     * 2. 2단계 fallback으로 후보 ID 수집 (최대 60개)
     * 3. 후보 상세 조회 (user, category, keywords 로딩)
     * 4. 자카드 유사도 계산 → 상위 60개 배치 구성 → 페이징
     * 5. 페이징된 숏츠에 대해 댓글 수, 좋아요 여부 배치 조회
     * 6. ShortsResponse + similarity로 변환
     *
     * @param shortsId      기준 숏츠 ID
     * @param currentUserId 현재 로그인 사용자 ID (비로그인 시 null)
     * @param offset        페이징 오프셋
     * @param limit         페이징 크기
     * @return 추천 숏츠 목록 (유사도 내림차순)
     */
    public RecommendationResponse getRecommendations(Long shortsId, Long currentUserId, int offset, int limit) {
        // 1. 기준 숏츠 조회 (keyword까지 fetch join)
        Shorts baseShorts = shortsRepository.findWithDetailsAndKeywordsById(shortsId)
                .orElseThrow(() -> new BaseException(SHORTS_NOT_FOUND, "해당 숏츠를 찾을 수 없습니다."));

        Set<String> baseKeywordNames = extractKeywordNames(baseShorts);

        // 2. 2단계 fallback으로 후보 ID 수집
        List<Long> candidateIds = collectCandidateIds(baseShorts);

        if (candidateIds.isEmpty()) {
            return RecommendationResponse.of(List.of(), offset, limit, 0);
        }

        // 3. 후보 상세 조회 (개별 fetch join)
        List<Shorts> candidateShortsList = loadCandidatesWithKeywords(candidateIds);

        // 4. 자카드 유사도 계산
        List<JaccardSimilarityCalculator.SimilarityResult> allResults =
                calculateAllSimilarities(baseKeywordNames, candidateShortsList);

        // 5. 배치 구성: 유사도 > 0 우선, 부족하면 유사도 0으로 채움
        List<JaccardSimilarityCalculator.SimilarityResult> positiveResults = allResults.stream()
                .filter(result -> result.similarity() > 0)
                .toList();
        List<JaccardSimilarityCalculator.SimilarityResult> zeroResults = allResults.stream()
                .filter(result -> result.similarity() == 0)
                .toList();

        List<JaccardSimilarityCalculator.SimilarityResult> batchResults = new ArrayList<>(positiveResults);
        if (batchResults.size() < BATCH_SIZE) {
            int remaining = BATCH_SIZE - batchResults.size();
            batchResults.addAll(zeroResults.stream().limit(remaining).toList());
        }

        int totalCount = Math.min(batchResults.size(), BATCH_SIZE);

        // 6. 페이징 적용
        List<JaccardSimilarityCalculator.SimilarityResult> pagedResults = batchResults.stream()
                .limit(totalCount)
                .skip(offset)
                .limit(limit)
                .toList();

        // 7. Response 생성 (페이징된 결과만 Map 조회)
        Set<Long> pagedShortsIds = pagedResults.stream()
                .map(JaccardSimilarityCalculator.SimilarityResult::shortsId)
                .collect(Collectors.toSet());

        List<Shorts> pagedShortsList = candidateShortsList.stream()
                .filter(s -> pagedShortsIds.contains(s.getId()))
                .toList();

        // 8. 댓글 수, 좋아요 여부 배치 조회 (페이징된 결과만)
        List<Long> pagedShortsIdList = pagedShortsList.stream()
                .map(Shorts::getId)
                .toList();

        Map<Long, Long> commentCounts = getCommentCounts(pagedShortsIdList);
        Set<Long> likedShortsIds = getLikedShortsIds(currentUserId, pagedShortsIdList);

        // 9. Shorts → ShortsResponse 변환 (commentCount, isLiked 포함)
        Map<Long, ShortsResponse> shortsResponseMap = pagedShortsList.stream()
                .collect(Collectors.toMap(
                        Shorts::getId,
                        shorts -> ShortsResponse.of(
                                shorts,
                                commentCounts.getOrDefault(shorts.getId(), 0L),
                                shorts.getViewCount(),
                                likedShortsIds.contains(shorts.getId())
                        )
                ));

        // 10. ShortsResponse + similarity → RecommendedShorts 생성
        List<RecommendationResponse.RecommendedShorts> recommendations = pagedResults.stream()
                .map(result -> {
                    ShortsResponse shortsResponse = shortsResponseMap.get(result.shortsId());
                    return RecommendationResponse.RecommendedShorts.of(
                            shortsResponse,
                            result.similarity()
                    );
                })
                .toList();

        return RecommendationResponse.of(recommendations, offset, limit, totalCount);
    }

    // ==================== 후보 수집 ====================

    /**
     * 2단계 fallback으로 추천 후보 ID를 수집
     * [전략]
     * 1차: 같은 카테고리 숏츠 (최신순, 최대 60개)
     * 2차: 랜덤 PUBLISHED 숏츠 (부족분 보충) — cold start 대응
     *
     * @param baseShorts 기준 숏츠 (keyword가 fetch join으로 로딩된 상태)
     * @return 중복 제거된 후보 숏츠 ID 목록 (최대 60개)
     */
    private List<Long> collectCandidateIds(Shorts baseShorts) {
        Long baseShortsId = baseShorts.getId();
        // LinkedHashSet: 삽입 순서 유지 + 중복 방지
        Set<Long> collectedIds = new LinkedHashSet<>();

        // 1차: 같은 카테고리 후보 (최신순)
        if (collectedIds.size() < MAX_CANDIDATES) {
            int remaining = MAX_CANDIDATES - collectedIds.size();
            List<Shorts> categoryCandidates = shortsRepository.findByCategoryIdAndStatusAndVisibility(
                    baseShorts.getCategory().getId(),
                    ShortsStatus.PUBLISHED,
                    ShortsVisibility.PUBLIC,
                    PageRequest.of(0, remaining, Sort.by(Sort.Direction.DESC, "createdAt"))
            ).getContent();

            categoryCandidates.stream()
                    .map(Shorts::getId)
                    .filter(id -> !id.equals(baseShortsId))
                    .forEach(collectedIds::add);
        }

        // 2차: 랜덤 fallback (cold start — 카테고리 부족할 때)
        if (collectedIds.size() < MAX_CANDIDATES) {
            int remaining = MAX_CANDIDATES - collectedIds.size();
            List<Shorts> randomCandidates = shortsRepository.findRecommendationCandidates(
                    baseShortsId,
                    ShortsStatus.PUBLISHED,
                    PageRequest.of(0, remaining));
            for (Shorts randomShorts : randomCandidates) {
                collectedIds.add(randomShorts.getId());
                if (collectedIds.size() >= MAX_CANDIDATES) break;
            }
        }

        return new ArrayList<>(collectedIds);
    }

    /**
     * 후보 숏츠 상세 조회 (user, category, keywords 로딩)
     * - Repository에 ID 목록 fetch join 메서드가 없어 개별 조회로 처리
     */
    private List<Shorts> loadCandidatesWithKeywords(List<Long> candidateIds) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery(
                        "SELECT DISTINCT s FROM Shorts s " +
                                "JOIN FETCH s.user " +
                                "JOIN FETCH s.category " +
                                "LEFT JOIN FETCH s.shortsKeywords sk " +
                                "LEFT JOIN FETCH sk.keyword " +
                                "WHERE s.id IN :ids",
                        Shorts.class)
                .setParameter("ids", candidateIds)
                .getResultList();
    }

    // ==================== 유사도 계산 ====================

    /**
     * 모든 후보에 대해 자카드 유사도를 일괄 계산
     * - fetch join으로 키워드가 이미 로딩된 상태이므로 추가 쿼리 없음
     *
     * @param baseKeywordNames    기준 숏츠의 키워드 이름 Set
     * @param candidateShortsList 후보 숏츠 목록 (키워드 로딩 완료)
     * @return 유사도 내림차순 정렬된 결과 목록
     */
    private List<JaccardSimilarityCalculator.SimilarityResult> calculateAllSimilarities(
            Set<String> baseKeywordNames,
            List<Shorts> candidateShortsList
    ) {
        List<JaccardSimilarityCalculator.SimilarityInput> inputs = candidateShortsList.stream()
                .map(candidate -> new JaccardSimilarityCalculator.SimilarityInput(
                        candidate.getId(),
                        extractKeywordNames(candidate)
                ))
                .toList();

        return JaccardSimilarityCalculator.calculateMultiple(baseKeywordNames, inputs);
    }

    /**
     * 숏츠의 키워드 displayName을 Set으로 추출
     * - fetch join으로 shortsKeywords → keyword가 이미 로딩된 상태 전제
     *
     * @param shorts 대상 숏츠
     * @return 키워드 displayName의 Set
     */
    private Set<String> extractKeywordNames(Shorts shorts) {
        return shorts.getShortsKeywords().stream()
                .map(sk -> sk.getKeyword().getDisplayName())
                .collect(Collectors.toSet());
    }

    private Map<Long, Long> getCommentCounts(List<Long> shortsIds) {
        if (shortsIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return commentRepository.countAllCommentsByShortsIds(shortsIds).stream()
                .collect(Collectors.toMap(
                        projection -> projection.getShortsId(),
                        projection -> projection.getCnt()
                ));
    }

    private Set<Long> getLikedShortsIds(Long currentUserId, List<Long> shortsIds) {
        if (currentUserId == null || shortsIds.isEmpty()) {
            return Collections.emptySet();
        }
        return shortsLikeRepository.findByUserIdAndShortsIdIn(currentUserId, shortsIds).stream()
                .map(like -> like.getShorts().getId())
                .collect(Collectors.toSet());
    }
}
