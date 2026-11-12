-- P12: sources (TECHSPEC §7.6). Fetched, cleaned, chunked and embedded documents; PUBLIC ones are shared across
-- runs and users (one row per canonical URL / DOI), PRIVATE ones (uploads) belong to one owner only.
-- claim / verdict tables arrive with P13.

CREATE TABLE upload (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  object_key   text NOT NULL,                  -- storage key; P12 keeps only the extracted text (no object store yet)
  filename     text NOT NULL,
  content_type text NOT NULL,
  size_bytes   bigint NOT NULL CHECK (size_bytes >= 0),
  sha256       bytea NOT NULL,
  purpose      text NOT NULL CONSTRAINT ck_upload_purpose CHECK (purpose IN ('RUN_ATTACHMENT','LIBRARY','RESUME')),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_upload_owner ON upload (owner_id);

CREATE TABLE source_document (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  visibility         text NOT NULL CONSTRAINT ck_source_visibility CHECK (visibility IN ('PUBLIC','PRIVATE')),
  owner_id           uuid REFERENCES app_user(id) ON DELETE CASCADE,   -- required when PRIVATE
  canonical_url      text,
  doi                text,
  upload_id          uuid REFERENCES upload(id) ON DELETE CASCADE,
  content_hash       bytea NOT NULL,                                   -- sha256 of the cleaned text
  title              text NOT NULL,
  authors            text[] NOT NULL DEFAULT '{}',
  site               text,
  published_at       date,
  type               text NOT NULL CONSTRAINT ck_source_type
                       CHECK (type IN ('PAPER','PREPRINT','DOCS','BLOG','NEWS','REPO','FORUM','WIKI','UPLOAD','OTHER')),
  credibility_tier   text NOT NULL CONSTRAINT ck_source_tier CHECK (credibility_tier IN ('HIGH','MEDIUM','LOW')),
  credibility_reason text NOT NULL,
  metadata           jsonb NOT NULL DEFAULT '{}',                      -- pages, stars, content type, …
  text               text NOT NULL,
  fetched_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_source_private_owner CHECK (visibility = 'PUBLIC' OR owner_id IS NOT NULL),
  CONSTRAINT ck_source_public_no_owner CHECK (visibility = 'PRIVATE' OR owner_id IS NULL)
);
CREATE UNIQUE INDEX ux_source_public_url ON source_document (canonical_url) WHERE visibility = 'PUBLIC' AND canonical_url IS NOT NULL;
CREATE UNIQUE INDEX ux_source_public_doi ON source_document (lower(doi))    WHERE visibility = 'PUBLIC' AND doi IS NOT NULL;
CREATE INDEX ix_source_owner ON source_document (owner_id) WHERE visibility = 'PRIVATE';
CREATE INDEX ix_source_hash ON source_document (content_hash);
CREATE INDEX ix_source_title_trgm ON source_document USING gin (title gin_trgm_ops);

CREATE TABLE source_chunk (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_id  uuid NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
  position   int  NOT NULL,
  text       text NOT NULL,
  char_start int  NOT NULL CHECK (char_start >= 0),
  char_end   int  NOT NULL,
  page       int,                                  -- PDF page (1-based) where the chunk starts; null for web pages
  embedding  vector(384) NOT NULL,                 -- multilingual-e5-small, "passage: " prefix
  CONSTRAINT ux_chunk_source_position UNIQUE (source_id, position),
  CONSTRAINT ck_chunk_span CHECK (char_end > char_start)
);
CREATE INDEX ix_chunk_embedding ON source_chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE run_source (
  run_id          uuid NOT NULL REFERENCES research_run(id) ON DELETE CASCADE,
  source_id       uuid NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
  sub_question_id uuid REFERENCES sub_question(id) ON DELETE CASCADE,
  status          text NOT NULL DEFAULT 'USED'
                    CONSTRAINT ck_run_source_status CHECK (status IN ('USED','REJECTED_BY_USER','UNAVAILABLE')),
  rank            smallint,
  PRIMARY KEY (run_id, source_id)
);
CREATE INDEX ix_run_source_source ON run_source (source_id);
