# Shortudy Backend Setup

## Local WSL
1. Install `OpenJDK 17`.
2. Install Docker Desktop on Windows and enable WSL integration for the distro that runs this repo.
3. Copy `.env.example` to `.env` and fill in real secrets.
4. Start infrastructure with `docker compose up -d db redis`.
5. Export the environment file before local Gradle runs:
   `set -a && source .env && set +a`
6. Run tests with `./gradlew test`.
7. Start the app locally with `./gradlew bootRun`, or start the full stack with `docker compose up -d --build`.

## Required Environment Variables
- `MYSQL_DATABASE`
- `MYSQL_ROOT_PASSWORD`
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `JWT_SECRET`
- `JWT_ACCESS_EXPIRATION`
- `JWT_REFRESH_EXPIRATION`
- `AWS_REGION`
- `AWS_S3_BUCKET`
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `SPRING_JPA_HIBERNATE_DDL_AUTO`
- `SPRING_PROFILES_ACTIVE`
- `APP_CORS_ALLOWED_ORIGINS`

## CORS
- Local frontend origins:
  `http://localhost:3000`
  `http://localhost:5173`
- Production frontend origin:
  `https://shortudy.vercel.app`

## AWS S3
- Apply `docs/aws/iam-policy.json` to the app IAM user.
- Apply `docs/aws/bucket-policy.json` to the S3 bucket after replacing `shortudy-assets` with the real bucket name.
- Apply `docs/aws/bucket-cors.json` to the bucket CORS settings.
- Current code expects public object reads for:
  `profiles/*`
  `videos/*`
  `thumbnails/*`

## EC2
1. Install Docker Engine and Docker Compose plugin.
2. Clone the repo.
3. Copy `.env.example` to `.env` and replace every placeholder.
4. Open security group ports:
   `22` from admin IPs only
   `80`, `443` publicly if a reverse proxy is added
   keep `3306`, `6379` closed
5. Start the stack with `docker compose up -d --build`.

## Notes
- `docker-compose.yml` binds MySQL and Redis to `127.0.0.1`, so they are not exposed externally even when the host is public.
- First boot uses Hibernate DDL with `update`; `src/main/resources/dad.sql` only seeds base category and keyword data.
