-- identity module, schema v1 (TECHSPEC §7.3). Extensions live here because this runs first.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE app_user (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email          text NOT NULL,
  email_verified boolean NOT NULL DEFAULT false,
  name           text,
  avatar_url     text,
  locale         text NOT NULL DEFAULT 'en',
  timezone       text NOT NULL DEFAULT 'Asia/Kolkata',
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);
-- One live account per email, case-insensitive; a soft-deleted account frees the email.
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email)) WHERE deleted_at IS NULL;

CREATE TABLE user_identity (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  provider         text NOT NULL CHECK (provider IN ('GOOGLE','GITHUB')),
  provider_subject text NOT NULL,              -- Google "sub", GitHub numeric id
  provider_login   text,                       -- GitHub username
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_user_identity_provider_subject UNIQUE (provider, provider_subject)
);
CREATE INDEX ix_user_identity_user ON user_identity (user_id);

CREATE TABLE personal_token (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name         text NOT NULL,
  token_prefix text NOT NULL,                  -- first 8 chars, for display
  token_hash   bytea NOT NULL,                 -- sha256(token); the token itself is never stored
  scopes       text[] NOT NULL DEFAULT '{mcp.read}',
  last_used_at timestamptz,
  expires_at   timestamptz,
  revoked_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_personal_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_personal_token_owner ON personal_token (owner_id);
