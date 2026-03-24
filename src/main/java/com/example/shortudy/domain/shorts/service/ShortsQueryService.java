package com.example.shortudy.domain.shorts.service;

import com.example.shortudy.domain.shorts.dto.ShortsResponse;
import com.example.shortudy.domain.shorts.dto.ShortsStatusDescriptionResponse;
import com.example.shortudy.domain.shorts.entity.Shorts;
import com.example.shortudy.domain.shorts.entity.ShortsInspectionResults;
import com.example.shortudy.domain.shorts.entity.ShortsStatus;
import com.example.shortudy.domain.shorts.repository.ShortsInspectionResultsRepository;
import com.example.shortudy.domain.shorts.repository.ShortsRepository;
import com.example.shortudy.domain.shorts.view.repository.RedisShortsViewCountRepository;
import com.example.shortudy.global.config.S3Service;
import com.example.shortudy.global.error.BaseException;
import com.example.shortudy.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 숏츠 조회 전용 서비스
 * 복잡한 집계 쿼리는 Repository에서 Object[]로 조회 후 DTO로 변환하여 반환합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShortsQueryService {

    private final ShortsRepository shortsRepository;
    private final ShortsInspectionResultsRepository shortsInspectionResultsRepository;
    private final RedisShortsViewCountRepository redisShortsViewCountRepository;
    private final S3Service s3Service;

    /**
     * 상세 조회 - DB 집계 데이터와 Redis 실시간 조회수를 통합하여 반환합니다.
     */
    public ShortsResponse getShortsDetails(Long shortsId, Long userId) {
        ShortsResponse response = shortsRepository.findResponseById(shortsId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.SHORTS_NOT_FOUND));
        
        // Keywords는 별도로 채워주거나, 엔티티 조회가 필요할 시 별도 로직 수행
        return enrich(fillKeywords(response, shortsId));
    }

    /**
     * 목록 조회 - 발행된 숏츠 목록을 집계 데이터와 함께 조회합니다.
     */
    public Page<ShortsResponse> getShortsList(Pageable pageable, Long userId) {
        Page<ShortsResponse> responses = shortsRepository.findResponsesByStatus(ShortsStatus.PUBLISHED, userId, pageable);
        return enrichAll(responses);
    }

    /**
     * 카테고리별 조회 - 특정 카테고리의 숏츠 목록을 집계 데이터와 함께 조회합니다.
     */
    public Page<ShortsResponse> getShortsByCategory(Long categoryId, Pageable pageable, Long userId) {
        Page<ShortsResponse> responses = shortsRepository.findResponsesByCategoryIdAndStatus(categoryId, ShortsStatus.PUBLISHED, userId, pageable);
        return enrichAll(responses);
    }

    /**
     * 인기 숏츠 조회 - 최근 N일간의 인기 숏츠를 집계 데이터와 함께 조회합니다.
     */
    public Page<ShortsResponse> getPopularShorts(Integer days, Pageable pageable, Long userId) {
        if (days == null || days <= 0) days = 30;
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        Page<ShortsResponse> responses = shortsRepository.findPopularResponses(since, userId, pageable);
        return enrichAll(responses);
    }

    /**
     * 내 쇼츠 조회 - 내가 작성한 숏츠 목록을 집계 데이터와 함께 조회합니다.
     */
    public Page<ShortsStatusDescriptionResponse> getMyShorts(Long userId, Pageable pageable) {
        Page<ShortsResponse> responses = shortsRepository.findMyResponses(userId, pageable);
        Page<ShortsResponse> enriched = enrichAll(responses);
        Page<ShortsResponse> responsesWithKeywords = fillKeywords(enriched);
        return fillInspectionDescriptions(responsesWithKeywords);
    }

    /**
     * DTO의 키워드 목록을 채워줍니다. (Batch Fetch 활용을 위해 엔티티에서 추출)
     */
    private ShortsResponse fillKeywords(ShortsResponse original, Long shortsId) {
        return shortsRepository.findWithDetailsAndKeywordsById(shortsId)
                .map(shorts -> new ShortsResponse(
                        original.shortsId(), original.title(), original.description(),
                        original.videoUrl(), original.thumbnailUrl(), original.durationSec(),
                        original.status(), original.visibility(), original.userId(), original.userNickname(),
                        original.userProfileUrl(), original.categoryId(), original.categoryName(),
                        shorts.getKeywords().stream().map(k -> k.getDisplayName()).toList(),
                        original.viewCount(), original.likeCount(), original.commentCount(),
                        original.createdAt(), original.updatedAt(), original.isLiked()
                )).orElse(original);
    }

    /**
     * 목록 응답의 키워드를 배치 조회로 채워 N+1을 방지한다.
     */
    private Page<ShortsResponse> fillKeywords(Page<ShortsResponse> responses) {
        if (responses.isEmpty()) {
            return responses;
        }

        List<Long> ids = responses.stream().map(ShortsResponse::shortsId).toList();
        Map<Long, List<String>> keywordsByShortsId = shortsRepository.findWithDetailsAndKeywordsByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        Shorts::getId,
                        shorts -> shorts.getKeywords().stream().map(k -> k.getDisplayName()).toList()
                ));

        return responses.map(original -> new ShortsResponse(
                original.shortsId(),
                original.title(),
                original.description(),
                original.videoUrl(),
                original.thumbnailUrl(),
                original.durationSec(),
                original.status(),
                original.visibility(),
                original.userId(),
                original.userNickname(),
                original.userProfileUrl(),
                original.categoryId(),
                original.categoryName(),
                keywordsByShortsId.getOrDefault(original.shortsId(), List.of()),
                original.viewCount(),
                original.likeCount(),
                original.commentCount(),
                original.createdAt(),
                original.updatedAt(),
                original.isLiked()
        ));
    }

    /**
     * 페이지 단위로 실시간 정보(조회수, 프로필 URL 등)를 통합합니다.
     */
    private Page<ShortsResponse> enrichAll(Page<ShortsResponse> responses) {
        Map<Long, Long> pendingCounts = redisShortsViewCountRepository.findPendingViewCounts();
        return responses.map(resp -> {
            Long pending = pendingCounts.getOrDefault(resp.shortsId(), 0L);
            return enrich(resp, resp.viewCount() + pending);
        });
    }

    /**
     * 단건에 대해 실시간 정보(조회수, 프로필 URL 등)를 통합합니다.
     */
    private ShortsResponse enrich(ShortsResponse response) {
        Map<Long, Long> pendingCounts = redisShortsViewCountRepository.findPendingViewCounts();
        Long pending = pendingCounts.getOrDefault(response.shortsId(), 0L);
        return enrich(response, response.viewCount() + pending);
    }

    /**
     * 실시간 데이터가 반영된 새로운 DTO를 생성합니다.
     */
    private ShortsResponse enrich(ShortsResponse original, long realTimeViewCount) {
        String fullProfileUrl = s3Service.getFileUrl(original.userProfileUrl());
        
        return new ShortsResponse(
                original.shortsId(), original.title(), original.description(),
                original.videoUrl(), original.thumbnailUrl(), original.durationSec(),
                original.status(), original.visibility(), original.userId(), original.userNickname(),
                fullProfileUrl, original.categoryId(), original.categoryName(),
                original.keywords(), realTimeViewCount, original.likeCount(),
                original.commentCount(), original.createdAt(), original.updatedAt(),
                original.isLiked()
        );
    }

    private Page<ShortsStatusDescriptionResponse> fillInspectionDescriptions(Page<ShortsResponse> responses) {
        if (responses.isEmpty()) {
            return responses.map(response -> ShortsStatusDescriptionResponse.of(response, resolveStatusDescription(response.status(), null)));
        }

        List<Long> shortsIds = responses.stream().map(ShortsResponse::shortsId).toList();
        Map<Long, ShortsInspectionResults> inspectionResultsByShortsId = shortsInspectionResultsRepository.findByShortsIdIn(shortsIds).stream()
                .collect(Collectors.toMap(
                        inspectionResults -> inspectionResults.getShorts().getId(),
                        Function.identity()
                ));

        return responses.map(response -> {
            ShortsInspectionResults inspectionResults = inspectionResultsByShortsId.get(response.shortsId());
            String description = resolveStatusDescription(response.status(), inspectionResults);
            return ShortsStatusDescriptionResponse.of(response, description);
        });
    }

    private String resolveStatusDescription(ShortsStatus shortsStatus, ShortsInspectionResults inspectionResults) {
        if (inspectionResults != null && inspectionResults.getReason() != null && !inspectionResults.getReason().isBlank()) {
            return inspectionResults.getReason();
        }

        return switch (shortsStatus) {
            case PENDING -> "업로드가 접수되었습니다.";
            case AI_CHECK -> "AI 검수가 진행 중입니다.";
            case PUBLISHED -> "게시된 숏츠입니다.";
            case REJECT -> "검수 결과 반려되었습니다.";
        };
    }
}
