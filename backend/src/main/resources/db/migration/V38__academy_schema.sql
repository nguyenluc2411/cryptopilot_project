-- T-114: the Academy, lessons of crypto theory for beginners. Anyone reads the published lessons and the glossary; a
-- Trader marks lessons as learned; an administrator writes them. The Academy reads no other module's tables.

-- A topic groups lessons on the Academy home page, in sort_order.
CREATE TABLE academy_topic (
    topic_id    uuid         NOT NULL,
    slug        varchar(80)  NOT NULL,
    name        varchar(120) NOT NULL,
    description varchar(500) NOT NULL,
    sort_order  integer      NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_academy_topic PRIMARY KEY (topic_id),
    CONSTRAINT uq_academy_topic_slug UNIQUE (slug)
);

-- A lesson. The body is Markdown. path_order places the lesson on the beginner path (null: not on it); sort_order
-- orders it inside its topic. app_link is a screen of the application the lesson leads to, such as /paper.
CREATE TABLE academy_article (
    article_id      uuid         NOT NULL,
    topic_id        uuid         NOT NULL,
    slug            varchar(120) NOT NULL,
    title           varchar(200) NOT NULL,
    summary         varchar(500) NOT NULL,
    body            text         NOT NULL,
    article_level   varchar(32)  NOT NULL,
    reading_minutes integer      NOT NULL,
    article_status  varchar(32)  NOT NULL,
    sort_order      integer      NOT NULL,
    path_order      integer,
    app_link        varchar(200),
    published_at    timestamptz,
    created_by      uuid,
    updated_by      uuid,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_academy_article PRIMARY KEY (article_id),
    CONSTRAINT uq_academy_article_slug UNIQUE (slug),
    CONSTRAINT uq_academy_article_path_order UNIQUE (path_order),
    CONSTRAINT fk_academy_article_topic FOREIGN KEY (topic_id) REFERENCES academy_topic (topic_id),
    CONSTRAINT fk_academy_article_created_by FOREIGN KEY (created_by) REFERENCES user_account (user_id),
    CONSTRAINT fk_academy_article_updated_by FOREIGN KEY (updated_by) REFERENCES user_account (user_id),
    CONSTRAINT ck_academy_article_level CHECK (article_level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    CONSTRAINT ck_academy_article_status CHECK (article_status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    CONSTRAINT ck_academy_article_reading_minutes CHECK (reading_minutes BETWEEN 1 AND 120),
    CONSTRAINT ck_academy_article_path_order CHECK (path_order IS NULL OR path_order > 0),
    CONSTRAINT ck_academy_article_published_at CHECK (article_status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX idx_academy_article_topic ON academy_article (topic_id, sort_order);
CREATE INDEX idx_academy_article_created_by ON academy_article (created_by);
CREATE INDEX idx_academy_article_updated_by ON academy_article (updated_by);

-- The glossary. A term may point at the lesson that explains it at length.
CREATE TABLE academy_term (
    term_id    uuid          NOT NULL,
    slug       varchar(120)  NOT NULL,
    term       varchar(120)  NOT NULL,
    definition varchar(1000) NOT NULL,
    article_id uuid,
    created_at timestamptz   NOT NULL,
    updated_at timestamptz   NOT NULL,
    version    bigint        NOT NULL DEFAULT 0,
    CONSTRAINT pk_academy_term PRIMARY KEY (term_id),
    CONSTRAINT uq_academy_term_slug UNIQUE (slug),
    CONSTRAINT fk_academy_term_article FOREIGN KEY (article_id) REFERENCES academy_article (article_id) ON DELETE SET NULL
);

CREATE INDEX idx_academy_term_article ON academy_term (article_id);

-- A lesson a Trader marked as learned; created_at is when. One row per Trader and lesson. An account is never
-- hard-deleted, so the row does not follow it; it follows its lesson, which an Admin may delete while unpublished.
CREATE TABLE academy_progress (
    progress_id uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    article_id  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_academy_progress PRIMARY KEY (progress_id),
    CONSTRAINT uq_academy_progress_user_article UNIQUE (user_id, article_id),
    CONSTRAINT fk_academy_progress_user FOREIGN KEY (user_id) REFERENCES user_account (user_id),
    CONSTRAINT fk_academy_progress_article FOREIGN KEY (article_id) REFERENCES academy_article (article_id) ON DELETE CASCADE
);

CREATE INDEX idx_academy_progress_article ON academy_progress (article_id);
