package com.example.shortudy.domain.shorts.service;

import com.example.shortudy.domain.category.entity.Category;
import com.example.shortudy.domain.category.repository.CategoryRepository;
import com.example.shortudy.domain.comment.repository.CommentRepository;
import com.example.shortudy.domain.keyword.service.KeywordService;
import com.example.shortudy.domain.like.repository.ShortsLikeRepository;
import com.example.shortudy.domain.shorts.dto.ShortsResponse;
import com.example.shortudy.domain.shorts.dto.ShortsUpdateRequest;
import com.example.shortudy.domain.shorts.entity.Shorts;
import com.example.shortudy.domain.shorts.entity.ShortsStatus;
import com.example.shortudy.domain.shorts.entity.ShortsVisibility;
import com.example.shortudy.domain.shorts.repository.ShortsInspectionResultsRepository;
import com.example.shortudy.domain.shorts.repository.ShortsRepository;
import com.example.shortudy.global.config.S3Service;
import com.example.shortudy.global.error.BaseException;

import com.example.shortudy.global.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ShortsService {

    private static final Set<String> VALID_SORT_PROPERTIES = Set.of(
        "id", "title", "durationSec", "createdAt", "updatedAt"
    );
    
    private static final Set<String> EXTENDED_VALID_SORT_PROPERTIES = Set.of(
        "id", "title", "durationSec", "createdAt", "updatedAt",
        "videoUrl", "thumbnailUrl", "description"
    );

    private static final String DEFAULT_SORT_PROPERTY = "id";
    private static final Sort.Direction DEFAULT_SORT_DIRECTION = Sort.Direction.DESC;

    private final ShortsRepository shortsRepository;
    private final CategoryRepository categoryRepository;
    private final KeywordService keywordService;
    private final S3Service s3Service;

    // 숏츠 삭제 시 댓글/좋아요도 다 날리기 위해 추가
    private final CommentRepository commentRepository;
    private final ShortsLikeRepository shortsLikeRepository;
    private final ShortsInspectionResultsRepository shortsInspectionResultsRepository;

    public ShortsService(
            ShortsRepository shortsRepository,
            CategoryRepository categoryRepository,
            KeywordService keywordService,
            S3Service s3Service,
            CommentRepository commentRepository,
            ShortsLikeRepository shortsLikeRepository,
            ShortsInspectionResultsRepository shortsInspectionResultsRepository
    ) {
        this.shortsRepository = shortsRepository;
        this.categoryRepository = categoryRepository;
        this.keywordService = keywordService;
        this.s3Service = s3Service;
        this.commentRepository = commentRepository;
        this.shortsLikeRepository = shortsLikeRepository;
        this.shortsInspectionResultsRepository = shortsInspectionResultsRepository;
    }


    public Shorts findShortsWithDetails(Long shortsId) {
        return shortsRepository.findWithDetailsAndKeywordsById(shortsId)
                .orElseThrow(() -> new BaseException(ErrorCode.SHORTS_NOT_FOUND));
    }

    public Page<Shorts> getShortsEntityList(Pageable pageable) {
        if (isRandomSortRequested(pageable)) {
            return shortsRepository.findRandomPublishedShorts(pageable);
        }

        if (hasValidSortProperties(pageable)) {
            return shortsRepository.findByStatusAndVisibility(ShortsStatus.PUBLISHED, ShortsVisibility.PUBLIC, pageable);
        }

        return shortsRepository.findRandomPublishedShorts(pageable);
    }

    public Page<Shorts> getShortsEntityByCategory(Long categoryId, Pageable pageable) {
        Pageable safePageable = createSafePageable(pageable);
        return shortsRepository.findByCategoryIdAndStatusAndVisibility(categoryId, ShortsStatus.PUBLISHED, ShortsVisibility.PUBLIC, safePageable);
    }

    public Page<Shorts> getPopularShortsEntities(Integer days, Pageable pageable) {
        if (days == null || days <= 0) days = 30;
        if (days > 90) days = 90;
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return shortsRepository.findPopularShorts(since, pageable);
    }

    public Page<Shorts> getMyShortsEntities(Long userId, Pageable pageable) {
        Pageable safePageable = createSafePageable(pageable);
        return shortsRepository.findByUserId(userId, safePageable);
    }

    @Transactional
    public ShortsResponse updateShorts(Long shortsId, ShortsUpdateRequest request, Long userId) {
        Shorts shorts = findShortsById(shortsId);
        Category category = findCategoryById(request.categoryId());
        
        validateUpdateRequest(request);

        if (!shorts.isWrittenBy(userId)) {
            throw new BaseException(ErrorCode.SHORTS_FORBIDDEN);
        }
        
        shorts.updateShorts(
            request.title(),
            request.description(),
            request.thumbnailUrl(),
            category,
            request.durationSec(),
            request.status()
        );

        if (request.visibility() != null) {
            shorts.changeVisibility(request.visibility());
        }

        if (request.keywords() != null) {
            shorts.clearKeywords();
            shortsRepository.flush();
            request.keywords().forEach(k -> shorts.addKeyword(keywordService.getValidKeyword(k)));
        }


        shortsRepository.saveAndFlush(shorts);

        boolean isLiked = shortsLikeRepository.existsByUserIdAndShortsId(userId, shortsId);
        String fullProfileUrl = shorts.getUser() != null ? s3Service.getFileUrl(shorts.getUser().getProfileUrl()) : null;
        return ShortsResponse.of(shorts, 0L, shorts.getViewCount(), isLiked, fullProfileUrl);
    }


    @Transactional
    public void deleteShorts(Long shortsId, Long userId) {
        validateShortsExists(shortsId);

        Shorts shorts = findShortsById(shortsId);
        if (!shorts.isWrittenBy(userId)) {
            throw new BaseException(ErrorCode.SHORTS_FORBIDDEN);
        }

        deleteShortsCascade(shortsId);
    }

    @Transactional
    public boolean deleteOrphanPendingShorts(Long shortsId) {
        // 고아 정리 경로에서는 예외를 던지지 않고 삭제 여부만 반환해 호출측 흐름을 단순화한다.
        Shorts shorts = shortsRepository.findById(shortsId).orElse(null);
        if (shorts == null) {
            return false;
        }
        if (!shorts.canBeDeletedAsUploadOrphan()) {
            return false;
        }

        deleteShortsCascade(shortsId);
        return true;
    }

    private void deleteShortsCascade(Long shortsId) {
        shortsLikeRepository.hardDeleteAllByShortsId(shortsId);
        commentRepository.deleteByShortsId(shortsId);
        shortsInspectionResultsRepository.deleteByShortsId(shortsId);
        shortsRepository.deleteById(shortsId);
    }

    private void validateShortsExists(Long shortsId) {
        if (!shortsRepository.existsById(shortsId)) {
            throw new BaseException(ErrorCode.SHORTS_NOT_FOUND);
        }
    }

    private Shorts findShortsById(Long shortsId) {
        return shortsRepository.findById(shortsId)
                .orElseThrow(() -> new BaseException(ErrorCode.SHORTS_NOT_FOUND));
    }


    private Category findCategoryById(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BaseException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    private void validateUpdateRequest(ShortsUpdateRequest request) {
        if (request.durationSec() != null && request.durationSec() <= 0) {
            throw new BaseException(ErrorCode.SHORTS_DURATION_INVALID);
        }
    }

    private boolean isRandomSortRequested(Pageable pageable) {
        return !pageable.getSort().isSorted();
    }

    private boolean hasValidSortProperties(Pageable pageable) {
        return pageable.getSort().stream()
            .allMatch(order -> VALID_SORT_PROPERTIES.contains(order.getProperty()));
    }

    private Pageable createSafePageable(Pageable pageable) {
        if (isRandomSortRequested(pageable)) {
            return createDefaultPageable(pageable);
        }

        if (hasExtendedValidSortProperties(pageable)) {
            return pageable;
        }

        return createDefaultPageable(pageable);
    }

    private boolean hasExtendedValidSortProperties(Pageable pageable) {
        return pageable.getSort().stream()
            .allMatch(order -> EXTENDED_VALID_SORT_PROPERTIES.contains(order.getProperty()));
    }

    private Pageable createDefaultPageable(Pageable originalPageable) {
        return PageRequest.of(
            originalPageable.getPageNumber(),
            originalPageable.getPageSize(),
            Sort.by(DEFAULT_SORT_DIRECTION, DEFAULT_SORT_PROPERTY)
        );
    }
}
