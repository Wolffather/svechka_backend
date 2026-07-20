CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL
);

CREATE TABLE diary_entry (
    id                     UUID PRIMARY KEY,
    user_id                UUID NOT NULL REFERENCES users (id),
    date                   DATE NOT NULL,
    status                 TEXT NOT NULL,
    raw_transcript         TEXT NOT NULL,
    ai_follow_up_question  TEXT,
    ai_follow_up_answer    TEXT,
    ai_summary             TEXT,
    created_at             TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX ux_diary_entry_user_date ON diary_entry (user_id, date);

CREATE TABLE weekly_insight (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users (id),
    week_start_date  DATE NOT NULL,
    insight_text     TEXT NOT NULL,
    created_at       TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX ux_weekly_insight_user_week ON weekly_insight (user_id, week_start_date);
