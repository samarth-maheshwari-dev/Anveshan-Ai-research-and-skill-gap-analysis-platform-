-- P14: reports (TECHSPEC §7.6). One report per run; sections hold TipTap JSON (the source of truth, with citation
-- nodes) and derived Markdown. Editing, locks and version history UI arrive with the Report Studio (P15).

CREATE TABLE report (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id         uuid NOT NULL REFERENCES research_run(id) ON DELETE CASCADE,
  owner_id       uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  title          text NOT NULL,
  language       text NOT NULL DEFAULT 'en' CONSTRAINT ck_report_language CHECK (language IN ('en','hi')),
  citation_style text NOT NULL DEFAULT 'IEEE' CONSTRAINT ck_report_style CHECK (citation_style IN ('IEEE','APA')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_report_run UNIQUE (run_id)
);
CREATE INDEX ix_report_owner ON report (owner_id, created_at DESC);

CREATE TABLE report_section (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  report_id   uuid NOT NULL REFERENCES report(id) ON DELETE CASCADE,
  position    smallint NOT NULL,
  key         text NOT NULL,                   -- e.g. key_findings, conflicts, references
  title       text NOT NULL,
  content_doc jsonb NOT NULL,                  -- TipTap JSON (source of truth, keeps citation nodes)
  content_md  text NOT NULL,                   -- derived Markdown (export, search)
  search_tsv  tsvector GENERATED ALWAYS AS (to_tsvector('simple', title || ' ' || content_md)) STORED,
  locked_by   uuid REFERENCES app_user(id) ON DELETE SET NULL,   -- L-FR-8 soft lock (S-4)
  locked_at   timestamptz,
  version     int NOT NULL DEFAULT 0,
  CONSTRAINT ux_section_report_position UNIQUE (report_id, position)
);
CREATE INDEX ix_section_tsv ON report_section USING gin (search_tsv);

CREATE TABLE report_version (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  report_id  uuid NOT NULL REFERENCES report(id) ON DELETE CASCADE,
  version    int NOT NULL,
  snapshot   jsonb NOT NULL,                   -- all sections at save time
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,   -- S-4: nullable so user deletion works
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_report_version UNIQUE (report_id, version)
);
