# Shortudy Backend

> Portfolio fork branch: <https://github.com/minji0828/wantbehelp-3rd-LXP/tree/refactor/shorts>  
> Original team repository: <https://github.com/35FUND/wantbehelp-3rd-LXP>

Shortudy는 짧은 학습 영상을 업로드하고 소비하는 숏폼 학습 플랫폼입니다. 이 저장소는 Spring Boot 기반 백엔드 API, JWT 인증, S3 업로드 흐름, 영상/플레이리스트/댓글/좋아요 도메인, Redis 기반 조회수 처리와 로컬 실행 구성을 담고 있습니다.

## 현재 백엔드 범위

- 회원가입, 로그인, 토큰 재발급, 로그아웃
- 사용자 프로필, 비밀번호 변경, 역할 변경
- S3 Presigned URL 기반 숏츠 업로드와 업로드 완료 처리
- 숏츠 목록, 상세, 인기, 내 숏츠, 수정, 삭제
- 플레이리스트 생성, 수정, 삭제, 순서 변경, 항목 관리
- 댓글, 답글, 신고
- 좋아요 토글과 내가 좋아요한 숏츠 조회
- 키워드, 카테고리, 추천 조회
- Redis 기반 조회수 집계와 주기적 DB 반영
- 업로드 세션 정리 스케줄러

## 기술 스택

- Java 17
- Spring Boot 3.5.8
- Spring Security / JWT
- Spring Data JPA
- MySQL
- Redis
- AWS S3
- Docker Compose
- Gradle Wrapper

## 핵심 코드 바로가기

| 영역 | 주요 파일 |
|---|---|
| 애플리케이션 진입점 | [`ShortsApplication.java`](src/main/java/com/example/shortudy/ShortsApplication.java) |
| 공통 응답 | [`ApiResponse.java`](src/main/java/com/example/shortudy/global/common/ApiResponse.java) |
| 보안/CORS | [`SecurityConfig.java`](src/main/java/com/example/shortudy/global/config/SecurityConfig.java) |
| 정적 리소스 매핑 | [`WebConfig.java`](src/main/java/com/example/shortudy/global/config/WebConfig.java) |
| 전역 예외 처리 | [`GlobalExceptionHandler.java`](src/main/java/com/example/shortudy/global/error/GlobalExceptionHandler.java) |
| 인증 API | [`AuthController.java`](src/main/java/com/example/shortudy/domain/user/controller/AuthController.java) |
| 사용자 API | [`UserController.java`](src/main/java/com/example/shortudy/domain/user/controller/UserController.java) |
| 숏츠 API | [`ShortsController.java`](src/main/java/com/example/shortudy/domain/shorts/controller/ShortsController.java) |
| 업로드 API | [`ShortsUploadController.java`](src/main/java/com/example/shortudy/domain/upload/controller/ShortsUploadController.java) |
| 플레이리스트 API | [`PlaylistController.java`](src/main/java/com/example/shortudy/domain/playlist/controller/PlaylistController.java) |
| 댓글 API | [`CommentController.java`](src/main/java/com/example/shortudy/domain/comment/controller/CommentController.java) |
| 답글 API | [`ReplyController.java`](src/main/java/com/example/shortudy/domain/comment/controller/ReplyController.java) |
| 좋아요 API | [`ShortsLikeController.java`](src/main/java/com/example/shortudy/domain/like/Controller/ShortsLikeController.java) |
| 키워드 API | [`KeywordController.java`](src/main/java/com/example/shortudy/domain/keyword/controller/KeywordController.java) |
| 카테고리 API | [`CategoryController.java`](src/main/java/com/example/shortudy/domain/category/controller/CategoryController.java) |
| 추천 API | [`RecommendationController.java`](src/main/java/com/example/shortudy/domain/recommendation/controller/RecommendationController.java) |
| 조회수 DB 반영 | [`ShortsViewCountFlushScheduler.java`](src/main/java/com/example/shortudy/domain/shorts/view/scheduler/ShortsViewCountFlushScheduler.java) |
| 업로드 세션 정리 | [`ShortsUploadCleanupScheduler.java`](src/main/java/com/example/shortudy/domain/upload/scheduler/ShortsUploadCleanupScheduler.java) |

## API 기준

주요 API는 `/api/v1/...` prefix를 사용합니다. 현재 별도 Swagger/OpenAPI 문서는 활성화되어 있지 않으므로, 실제 API 확인은 각 controller 파일을 기준으로 합니다.

대표 진입점:

- `/api/v1/auth` - 로그인, 재발급, 로그아웃
- `/api/v1/users` - 회원가입, 프로필, 비밀번호, 역할
- `/api/v1/shorts` - 숏츠 목록/상세/수정/삭제
- `/api/v1/uploads/shorts` - 업로드 URL 발급, 완료, 상태 확인
- `/api/v1/playlists` - 플레이리스트 관리
- `/api/v1/comments`, `/api/v1/replies` - 댓글/답글
- `/api/v1/shorts/*/likes` - 좋아요

## 로컬 실행

자세한 절차는 [`SETUP.md`](SETUP.md)를 기준으로 합니다.

```bash
cp .env.example .env
docker compose up -d db redis
set -a && source .env && set +a
./gradlew bootRun
```

전체 스택을 Docker Compose로 올릴 수도 있습니다.

```bash
docker compose up -d --build
```

## 필수 환경변수

실제 secret 값은 저장소에 커밋하지 않습니다. 필요한 키 목록은 [`.env.example`](.env.example)과 [`SETUP.md`](SETUP.md)를 확인합니다.

주요 항목:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`
- `JWT_SECRET`, `JWT_ACCESS_EXPIRATION`, `JWT_REFRESH_EXPIRATION`
- `AWS_REGION`, `AWS_S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- `APP_CORS_ALLOWED_ORIGINS`

## 검증

```bash
./gradlew test
```

대표 테스트 파일:

- [`ShortsLikeIntegrationTest.java`](src/test/java/com/example/shortudy/domain/like/ShortsLikeIntegrationTest.java)
- [`ShortsApplicationTests.java`](src/test/java/com/example/shortudy/ShortsApplicationTests.java)

## 배포/인프라 참고

- [`docker-compose.yml`](docker-compose.yml): app, MySQL, Redis 로컬 구성
- [`Dockerfile`](Dockerfile): 앱 컨테이너 빌드 기준
- [`docs/aws/iam-policy.json`](docs/aws/iam-policy.json): S3 IAM 정책 예시
- [`docs/aws/bucket-policy.json`](docs/aws/bucket-policy.json): S3 bucket policy 예시
- [`docs/aws/bucket-cors.json`](docs/aws/bucket-cors.json): S3 CORS 예시

## 현재 한계

- 루트 README는 검증 동선 제공을 위해 추가한 문서이며, 상세 API 명세는 controller 코드를 기준으로 확인합니다.
- OpenAPI 생성 설정은 활성화되어 있지 않습니다.
- 로컬 실행은 `.env`와 MySQL, Redis, S3 설정에 의존합니다.
- 테스트 커버리지는 좋아요 도메인 중심으로 확인되며, 전체 도메인 완전 검증을 의미하지 않습니다.
- 일부 endpoint에는 TODO/deprecated 표시가 남아 있어 실제 사용 전 controller와 service 구현을 함께 확인해야 합니다.
