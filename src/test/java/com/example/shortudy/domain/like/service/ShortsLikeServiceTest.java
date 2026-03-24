package com.example.shortudy.domain.like.service;

import com.example.shortudy.domain.category.entity.Category;
import com.example.shortudy.domain.like.dto.LikeToggleResponse;
import com.example.shortudy.domain.like.dto.MyLikedShortsResponse;
import com.example.shortudy.domain.like.dto.ShortsLikeResponse;
import com.example.shortudy.domain.like.dto.SortStandard;
import com.example.shortudy.domain.like.entity.ShortsLike;
import com.example.shortudy.domain.like.repository.ShortsLikeRepository;
import com.example.shortudy.domain.shorts.entity.Shorts;
import com.example.shortudy.domain.shorts.entity.ShortsStatus;
import com.example.shortudy.domain.shorts.repository.ShortsRepository;
import com.example.shortudy.domain.user.entity.User;
import com.example.shortudy.domain.user.entity.UserRole;
import com.example.shortudy.domain.user.repository.UserRepository;
import com.example.shortudy.global.config.JpaAuditConfig;
import com.example.shortudy.global.config.S3Service;
import com.example.shortudy.global.error.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({ShortsLikeService.class, JpaAuditConfig.class})
@DisplayName("Like Service 테스트")
class ShortsLikeServiceTest {

    @MockitoBean
    private S3Service s3Service;

    @Autowired
    private ShortsLikeService shortsLikeService;

    @Autowired
    private ShortsLikeRepository shortsLikeRepository;

    @Autowired
    private ShortsRepository shortsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    private User user, user2;
    private Shorts shorts, shorts2;

    @BeforeEach
    void setUp() {
        user = User.create("test@example.com", "password", "nickname", UserRole.USER);
        user2 = User.create("test2@example.com", "password", "nickname2", UserRole.USER);
        userRepository.save(user);
        userRepository.save(user2);

        Category category = new Category("category");
        em.persist(category);

        shorts = new Shorts(user, category, "title", "description",
                "http://video.url", "http://thumbnail.url", 500, ShortsStatus.PUBLISHED);
        shorts2 = new Shorts(user2, category, "title2", "description2",
                "http://video2.url", "http://thumbnail2.url", 5700, ShortsStatus.PUBLISHED);
        shortsRepository.save(shorts);
        shortsRepository.save(shorts2);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("TC-SLS-001: 좋아요가 없는 상태에서 호출하면 새로운 좋아요가 생성되고, 카운트 1이 증가한다")
    void shouldCreateLikeAndPlusCountOne_WhenLikeNotExists() {

        // when
        LikeToggleResponse response = shortsLikeService.toggleLike(user.getId(), shorts.getId());
        Optional<ShortsLike> savedLike = shortsLikeRepository.findWithDeleted(user.getId(), shorts.getId());

        // then
        assertTrue(response.isLiked(), "좋아요가 등록상태야 합니다");
        assertEquals(1, response.likeCount(), "좋아요가 취소되면 총 카운트가 0이어야 합니다");

        assertTrue(savedLike.isPresent(), "등록된 좋아요가 Soft delete가 아닌 상태로 조회되어야 합니다.");
    }

    @Test
    @DisplayName("TC-SLS-002: 이미 좋아요가 있는 상태에서 호출하면 좋아요를 취소(Soft delete)한다")
    void shouldSoftDeleteLike_WhenLikeExists() {
        // given
        shortsLikeService.toggleLike(user.getId(), shorts.getId());
        em.flush();
        em.clear();

        // when
        LikeToggleResponse response = shortsLikeService.toggleLike(user.getId(), shorts.getId());

        // then
        assertFalse(response.isLiked(), "좋아요가 취소상태야 합니다");
        assertEquals(0, response.likeCount(), "좋아요가 취소되면 총 카운트가 0이어야 합니다");

        Optional<ShortsLike> activeLike = shortsLikeRepository.findByUserIdAndShortsId(user.getId(), shorts.getId());
        assertTrue(activeLike.isEmpty(), "삭제 된 좋아요는 조회될 수 없습니다");

        Optional<ShortsLike> deletedLike = shortsLikeRepository.findWithDeleted(user.getId(), shorts.getId());
        assertTrue(deletedLike.isPresent(), "soft delete 된 좋아요가 조회되어야 합니다");
        assertTrue(deletedLike.get().isDeleted(), "soft delete 된 좋아요는 삭제 날짜가 기록되어야 합니다");
    }

    @Test
    @DisplayName("TC-SLS-003: 좋아요 재등록(복구): 취소했던 좋아요를 다시 누르면 새로운 행이 생기지 않고 기존 데이터가 복구된다")
    void shouldRestoreLike_WhenClickDeletedLike() {
        // given
        shortsLikeService.toggleLike(user.getId(), shorts.getId()); // 등록
        shortsLikeService.toggleLike(user.getId(), shorts.getId()); // 취소
        em.flush();
        em.clear();

        // when
        LikeToggleResponse response = shortsLikeService.toggleLike(user.getId(), shorts.getId());

        // then
        assertTrue(response.isLiked(), "좋아요가 복구되어야 합니다");
        assertEquals(1, response.likeCount(), "좋아요 총 횟수가 1 증가해야 합니다");

        Optional<ShortsLike> restoredLike = shortsLikeRepository.findWithDeleted(user.getId(), shorts.getId());
        assertTrue(restoredLike.isPresent(), "복구 된 좋아요는 조회되어야 합니다");
        assertFalse(restoredLike.get().isDeleted(), "복구 된 좋아요의 삭제 날짜는 null이어야 합니다");
    }

    @Test
    @DisplayName("TC-SLS-004: 존재하지 않는 숏츠에 좋아요를 시도하면 예외가 발생한다")
    void shouldThrowException_WhenShortsNotFound() {
        // given
        Long notExistingId = 9999L; // 실제 DB에 없을 만한 번호 사용

        // when & then
        assertThrows(BaseException.class,
                () -> shortsLikeService.toggleLike(user.getId(), notExistingId),
                "존재하지 않는 숏츠에 좋아요를 할 수 없습니다");
    }

    @Test
    @DisplayName("TC-SLS-005: 인기순 정렬 요청 시 Repository의 인기순 조회 메서드를 호출한다")
    void getMyLikedShorts_Popular_Success() {
        // given
        shortsLikeService.toggleLike(user.getId(), shorts.getId());
        shortsLikeService.toggleLike(user.getId(), shorts2.getId());
        shortsLikeService.toggleLike(user2.getId(), shorts2.getId());

        em.flush();
        em.clear();

        Pageable pageable = PageRequest.of(0, 10);

        //when
        Page<MyLikedShortsResponse> response = shortsLikeService.getMyLikedShorts(user.getId(), SortStandard.POPULAR.getValue(), pageable);

        // then
        assertEquals(2, response.getContent().size(), "인기순 조회 시 요청에 맞는 결과가 나와야 합니다");
        assertEquals("title2", response.getContent().get(0).title(), "인기순 조회 시 조건에 맞는 숏츠의 제목이어야 합니다");
    }

    @Test
    @DisplayName("TC-SLS-006: 특정 숏츠에 대해 이미 좋아요를 누른 경우 true를 반환한다")
    void shouldReturnTrue_WhenUserAlreadyLikedShorts() {
        // given
        shortsLikeService.toggleLike(user.getId(), shorts.getId());
        em.flush();
        em.clear();

        // when
        ShortsLikeResponse response = shortsLikeService.getShortsLikeStatus(user.getId(), shorts.getId());

        // then
        assertTrue(response.isLiked(), "이미 좋아요를 누른 상태이므로 true여야 합니다");
        assertEquals(shorts.getId(), response.shortsId());
        assertEquals(user.getId(), response.userId());
    }

    @Test
    @DisplayName("TC-SLS-007: 특정 숏츠에 대해 좋아요를 누르지 않은 경우 false를 반환한다")
    void shouldReturnFalse_WhenUserHasNotLikedShorts() {
        // when
        ShortsLikeResponse response = shortsLikeService.getShortsLikeStatus(user.getId(), shorts.getId());

        // then
        assertFalse(response.isLiked(), "좋아요를 누르지 않았으므로 false여야 합니다");
        assertEquals(shorts.getId(), response.shortsId());
        assertEquals(user.getId(), response.userId());
    }

    @Test
    @DisplayName("TC-SLS-008: 좋아요를 눌렀다가 취소한 경우(Soft Delete) false를 반환해야 한다")
    void shouldReturnFalse_WhenLikeWasSoftDeleted() {
        // given
        shortsLikeService.toggleLike(user.getId(), shorts.getId()); // 등록
        shortsLikeService.toggleLike(user.getId(), shorts.getId()); // 취소 (Soft Delete)
        em.flush();
        em.clear();

        // when
        ShortsLikeResponse response = shortsLikeService.getShortsLikeStatus(user.getId(), shorts.getId());

        // then
        assertFalse(response.isLiked(), "좋아요를 취소했으므로 false여야 합니다");
    }
}
