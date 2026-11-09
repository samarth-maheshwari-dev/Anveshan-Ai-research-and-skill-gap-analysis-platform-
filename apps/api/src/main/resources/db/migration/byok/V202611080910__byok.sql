-- byok module, schema v1 (TECHSPEC §7.4). Keys are stored only as Tink AEAD ciphertext.
CREATE TABLE ai_connection (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  kind           text NOT NULL CHECK (kind IN ('LLM','SEARCH')),
  provider       text NOT NULL CHECK (provider IN ('GEMINI','GROQ','OPENROUTER','OPENAI','ANTHROPIC','CUSTOM','TAVILY')),
  label          text NOT NULL,
  base_url       text NOT NULL,                -- preset value or validated custom https URL
  key_ciphertext bytea NOT NULL,
  key_last4      text NOT NULL CHECK (length(key_last4) <= 4),
  status         text NOT NULL DEFAULT 'UNVERIFIED'
                 CHECK (status IN ('UNVERIFIED','VERIFIED','FAILING','DISABLED')),
  status_detail  text,                         -- human-readable last error, no secrets
  last_tested_at timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_connection_owner ON ai_connection (owner_id);

CREATE TABLE slot_route (
  owner_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  slot          text NOT NULL CHECK (slot IN ('PLAN','EXTRACT','VERIFY','WRITE','ASK','INTERVIEW')),
  position      smallint NOT NULL CHECK (position BETWEEN 0 AND 4),   -- fallback order
  connection_id uuid NOT NULL REFERENCES ai_connection(id) ON DELETE CASCADE,
  model         text NOT NULL,
  PRIMARY KEY (owner_id, slot, position)
);
CREATE INDEX ix_slot_route_connection ON slot_route (connection_id);

CREATE TABLE route_quota (                     -- learned quota per (connection, model, day)
  connection_id  uuid NOT NULL REFERENCES ai_connection(id) ON DELETE CASCADE,
  model          text NOT NULL,
  day            date NOT NULL,
  requests       int  NOT NULL DEFAULT 0,
  tokens_in      int  NOT NULL DEFAULT 0,
  tokens_out     int  NOT NULL DEFAULT 0,
  remaining_requests_hint int,                 -- from x-ratelimit-* headers when present
  remaining_tokens_hint   int,
  cooldown_until timestamptz,
  PRIMARY KEY (connection_id, model, day)
);

CREATE TABLE llm_call (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  run_id        uuid,                          -- logical link (no FK): assist/ask/interview calls have none
  step_id       uuid,
  slot          text NOT NULL CHECK (slot IN ('PLAN','EXTRACT','VERIFY','WRITE','ASK','INTERVIEW')),
  connection_id uuid,                          -- no FK: usage history survives deleting a key
  model         text NOT NULL,
  tokens_in     int,
  tokens_out    int,
  latency_ms    int NOT NULL,
  status        text NOT NULL CHECK (status IN ('OK','RATE_LIMITED','AUTH_ERROR','INVALID_OUTPUT','ERROR')),
  error_code    text,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_llm_call_owner_time ON llm_call (owner_id, created_at DESC);
CREATE INDEX ix_llm_call_run ON llm_call (run_id) WHERE run_id IS NOT NULL;
