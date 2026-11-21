-- llm module (P16): users no longer bring AI keys; a developer-configured gateway chain answers every call.
DROP TABLE IF EXISTS route_quota CASCADE;
DROP TABLE IF EXISTS slot_route CASCADE;
DROP TABLE IF EXISTS ai_connection CASCADE;

-- llm_call now records which gateway endpoint (by its config name) answered, never a key
ALTER TABLE llm_call DROP COLUMN IF EXISTS connection_id;
ALTER TABLE llm_call ADD COLUMN IF NOT EXISTS endpoint text;

-- research runs started per user per UTC day (anveshan.llm.daily-runs-per-user)
CREATE TABLE llm_daily_usage (
  owner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  day      date NOT NULL,
  runs     int  NOT NULL DEFAULT 0 CHECK (runs >= 0),
  PRIMARY KEY (owner_id, day)
);
