# svechka-backend

Spring Boot backend for "Свечка" — a voice diary with AI dialogue. Modules: `auth` (JWT),
`diary` (recording pipeline), `insight` (weekly retrospectives), `ai` (transcription +
dialogue engine clients).

## Local development

```bash
cp .env.example .env   # fill in AI_API_KEY at minimum
docker compose up -d   # starts a local Postgres on 5432
export $(cat .env | xargs)
./mvnw spring-boot:run
```

The app reads its Postgres and AI provider config from environment variables (see
`.env.example` and `src/main/resources/application.yml`). Flyway migrations run
automatically on startup from `src/main/resources/db/migration`.

## Tests

```bash
./mvnw test
```

Integration tests use Testcontainers and need a working Docker daemon.

## AI provider

`TranscriptionClient` and `DialogEngineClient` (package `com.svechka.backend.ai`) are the
only interfaces the rest of the app depends on. The default implementation
(`com.svechka.backend.ai.openai`) talks to any OpenAI-compatible API — swap providers
(YandexGPT, GigaChat, etc.) via `AI_BASE_URL` / `AI_API_KEY` / `AI_TRANSCRIPTION_MODEL` /
`AI_CHAT_MODEL` only, no code changes needed as long as the provider mirrors the
`/audio/transcriptions` and `/chat/completions` contracts. System prompts live under
`src/main/resources/prompts/`.

## Full stack

This repo only contains the backend. Frontend, Caddy reverse proxy, and the
docker-compose file that wires the whole stack together (backend + frontend + Caddy +
Postgres) live in the sibling `svechka` repository.
