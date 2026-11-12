-- P13: claims, their use in runs, and verdicts (TECHSPEC §7.6). A claim is anchored to an exact quote of one chunk
-- (offsets computed by code, L1) and reused across runs for the same chunk + prompt version (token diet TD-4).

CREATE TABLE claim (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_id      uuid NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
  chunk_id       uuid NOT NULL REFERENCES source_chunk(id) ON DELETE CASCADE,
  text           text NOT NULL,
  quote          text NOT NULL,                -- exact substring of the chunk (§12.6)
  quote_start    int  NOT NULL,                -- offsets into the chunk text, computed by code, not by the LLM
  quote_end      int  NOT NULL,
  prompt_version text NOT NULL,
  embedding      vector(384) NOT NULL,         -- claims embedded as passages
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_claim_span CHECK (quote_start >= 0 AND quote_end > quote_start)
);
CREATE INDEX ix_claim_chunk_prompt ON claim (chunk_id, prompt_version);
CREATE INDEX ix_claim_embedding ON claim USING hnsw (embedding vector_cosine_ops);

CREATE TABLE run_claim (
  run_id          uuid NOT NULL REFERENCES research_run(id) ON DELETE CASCADE,
  claim_id        uuid NOT NULL REFERENCES claim(id) ON DELETE CASCADE,
  sub_question_id uuid REFERENCES sub_question(id) ON DELETE CASCADE,
  trust_label     text CONSTRAINT ck_run_claim_label CHECK (trust_label IN ('SUPPORTED','WEAK','CONFLICTING','NO_SOURCE')),
  label_reason    jsonb,                       -- which verdicts/sources produced the label
  important       boolean NOT NULL DEFAULT false,   -- L-FR-4
  review_status   text NOT NULL DEFAULT 'NONE'
                    CONSTRAINT ck_run_claim_review CHECK (review_status IN ('NONE','PENDING','APPROVED','REJECTED')),
  reviewed_by     uuid REFERENCES app_user(id) ON DELETE SET NULL,   -- S-5
  reviewed_at     timestamptz,                                        -- S-5
  PRIMARY KEY (run_id, claim_id)
);
CREATE INDEX ix_run_claim_claim ON run_claim (claim_id);

CREATE TABLE verdict (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id     uuid NOT NULL REFERENCES research_run(id) ON DELETE CASCADE,
  claim_id   uuid NOT NULL REFERENCES claim(id) ON DELETE CASCADE,
  chunk_id   uuid NOT NULL REFERENCES source_chunk(id) ON DELETE CASCADE,
  verdict    text NOT NULL CONSTRAINT ck_verdict CHECK (verdict IN ('SUPPORTS','CONTRADICTS','INSUFFICIENT','PARTIAL')),
  reason     text CONSTRAINT ck_verdict_reason CHECK (reason IS NULL OR length(reason) <= 200),
  CONSTRAINT ux_verdict_run_claim_chunk UNIQUE (run_id, claim_id, chunk_id)
);
CREATE INDEX ix_verdict_claim ON verdict (claim_id);
