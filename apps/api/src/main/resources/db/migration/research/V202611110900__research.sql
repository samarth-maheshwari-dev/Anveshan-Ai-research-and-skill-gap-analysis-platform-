-- research module (TECHSPEC §7.5 + SCHEMA §13 S-9). Cross-module FKs are added by the later modules:
-- research_run.workspace_id (team, S-1), research_run.skill_target_id (skills), sub_question.assigned_to (team, S-3).

CREATE TABLE template (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                text NOT NULL,
  name                text NOT NULL,
  form_defaults       jsonb NOT NULL,
  sections            jsonb NOT NULL,          -- ordered [{key,title,instructions}]
  writer_instructions text NOT NULL,
  version             int NOT NULL DEFAULT 1,
  CONSTRAINT ux_template_code UNIQUE (code)
);

CREATE TABLE research_run (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  workspace_id    uuid,
  topic           text NOT NULL CHECK (length(topic) BETWEEN 10 AND 500),
  config          jsonb NOT NULL,
  template_id     uuid REFERENCES template(id),
  skill_target_id uuid,
  status          text NOT NULL DEFAULT 'PLANNING' CHECK (status IN
                  ('PLANNING','AWAITING_APPROVAL','RUNNING','PAUSED','COMPLETED','FAILED','CANCELLED')),
  pause_reason    text CHECK (pause_reason IN ('USER','AI_LIMIT_REACHED','SEARCH_LIMIT_REACHED')),
  resume_at       timestamptz,
  progress        numeric(5,2) NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  estimate        jsonb,
  idempotency_key text,
  version         int NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  completed_at    timestamptz,
  mode            text GENERATED ALWAYS AS (config->>'mode') STORED,   -- S-9: lists filter mode IS NULL
  CONSTRAINT ux_run_owner_idempotency UNIQUE (owner_id, idempotency_key)
);
CREATE INDEX ix_run_owner_status ON research_run (owner_id, status, created_at DESC);
CREATE INDEX ix_run_resume ON research_run (resume_at) WHERE status = 'PAUSED' AND resume_at IS NOT NULL;
CREATE INDEX ix_run_template ON research_run (template_id);

CREATE TABLE sub_question (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id       uuid NOT NULL REFERENCES research_run(id) ON DELETE CASCADE,
  position     smallint NOT NULL,
  text         text NOT NULL,
  source_types text[] NOT NULL,
  queries      text[] NOT NULL,
  assigned_to  uuid,
  CONSTRAINT ux_sub_question_run_position UNIQUE (run_id, position)
);

CREATE TABLE research_step (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id          uuid NOT NULL REFERENCES research_run(id) ON DELETE CASCADE,
  seq             int  NOT NULL,
  stage           smallint NOT NULL,
  kind            text NOT NULL CHECK (kind IN
                  ('PLAN','SEARCH','FETCH','RELEVANCE','EXTRACT','VERIFY','LABEL','WRITE_SECTION','ASSEMBLE')),
  sub_question_id uuid REFERENCES sub_question(id) ON DELETE CASCADE,
  input           jsonb NOT NULL,
  output          jsonb,                       -- checkpoint; DONE steps are never re-run (L4)
  status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','SKIPPED')),
  attempts        smallint NOT NULL DEFAULT 0,
  last_error      text,
  idempotency_key text NOT NULL,               -- run_id + kind + seq
  started_at      timestamptz,
  finished_at     timestamptz,
  CONSTRAINT ux_step_idempotency UNIQUE (idempotency_key),
  CONSTRAINT ux_step_run_seq UNIQUE (run_id, seq)
);
CREATE INDEX ix_step_run_status ON research_step (run_id, status);
CREATE INDEX ix_step_sub_question ON research_step (sub_question_id);
CREATE INDEX ix_step_stale ON research_step (started_at) WHERE status = 'RUNNING';

CREATE TABLE run_event (                       -- SSE replay log (E-FR-1)
  id         bigserial PRIMARY KEY,            -- used as the SSE "id:" field
  run_id     uuid NOT NULL REFERENCES research_run(id) ON DELETE CASCADE,
  type       text NOT NULL,
  payload    jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_run_event_run_id ON run_event (run_id, id);
