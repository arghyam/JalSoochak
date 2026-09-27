-- ============================================================
-- Migration: V38 - Language catalog single source of truth
-- ============================================================
-- The language catalog (name/alias -> numeric language_id, and name -> locale
-- code) was previously duplicated as hardcoded Java maps in
--   * GlificContactSyncService.buildLanguageMap()   (name/alias -> language_id)
--   * WelcomeMessageService.mapToLocaleCode()        (name/alias -> locale code)
--
-- Those maps stay in code as a defensive fallback, but this table makes the
-- catalog data-driven: onboarding a new language becomes an INSERT, not a code
-- change. LanguageCatalogRepository reads these tables (cached) and the two
-- services consult it first, falling back to the hardcoded maps when a row is
-- absent (or the repository / table is unavailable).
--
-- Aliases are stored NORMALISED to the form the repository looks them up by:
-- lower-cased, with every run of non-alphanumeric characters collapsed to a
-- single ASCII space, then trimmed (e.g. "as_in" and "as in" -> "as in",
-- "Sign Language" -> "sign language", "rw_rw"/"rw rw" -> "rw rw"). This lets the
-- two callers (which normalise with '_' and ' ' respectively) share one row set.
--
-- Written idempotently (IF NOT EXISTS + ON CONFLICT DO NOTHING) so it is a safe
-- no-op where the tables already exist and re-runnable on fresh databases.
-- ============================================================

CREATE TABLE IF NOT EXISTS common_schema.language_master (
    language_id    INTEGER      NOT NULL,
    canonical_name VARCHAR(64)  NOT NULL,
    locale_code    VARCHAR(16)  NOT NULL,

    CONSTRAINT language_master_pkey PRIMARY KEY (language_id)
);

CREATE TABLE IF NOT EXISTS common_schema.language_alias (
    alias        VARCHAR(64) NOT NULL,   -- normalised: lower-case, non-alnum runs -> single space, trimmed
    language_id  INTEGER     NOT NULL,

    CONSTRAINT language_alias_pkey PRIMARY KEY (alias),
    CONSTRAINT language_alias_language_fk FOREIGN KEY (language_id)
        REFERENCES common_schema.language_master (language_id)
);

CREATE INDEX IF NOT EXISTS idx_language_alias_language_id
    ON common_schema.language_alias (language_id);

-- Canonical languages (ids mirror the existing hardcoded catalog; gaps at 19 and
-- 23+ are intentional — they were never assigned in code).
INSERT INTO common_schema.language_master (language_id, canonical_name, locale_code) VALUES
    (1,  'English',       'en'),
    (2,  'Hindi',         'hi'),
    (3,  'Tamil',         'ta'),
    (4,  'Kannada',       'kn'),
    (5,  'Malayalam',     'ml'),
    (6,  'Telugu',        'te'),
    (7,  'Odia',          'or'),
    (8,  'Assamese',      'as'),
    (9,  'Gujarati',      'gu'),
    (10, 'Bengali',       'bn'),
    (11, 'Punjabi',       'pa'),
    (12, 'Marathi',       'mr'),
    (13, 'Urdu',          'ur'),
    (14, 'Spanish',       'es'),
    (15, 'Sign Language', 'isl'),
    (16, 'French',        'fr'),
    (17, 'Swahili',       'sw'),
    (18, 'Kinyarwanda',   'rw'),
    (20, 'Malay',         'ms'),
    (21, 'Gondi',         'gon'),
    (22, 'Indonesian',    'id')
ON CONFLICT (language_id) DO NOTHING;

-- Aliases (union of both hardcoded catalogs), stored in normalised form.
INSERT INTO common_schema.language_alias (alias, language_id) VALUES
    ('english', 1), ('en', 1),
    ('hindi', 2), ('hi', 2),
    ('tamil', 3), ('ta', 3),
    ('kannada', 4), ('kn', 4),
    ('malayalam', 5), ('ml', 5),
    ('telugu', 6), ('te', 6),
    ('odia', 7), ('oriya', 7), ('or', 7),
    ('assamese', 8), ('as', 8), ('as in', 8),
    ('gujarati', 9), ('gu', 9),
    ('bengali', 10), ('bn', 10),
    ('punjabi', 11), ('pa', 11),
    ('marathi', 12), ('mr', 12),
    ('urdu', 13), ('ur', 13),
    ('spanish', 14), ('es', 14),
    ('sign language', 15), ('isl', 15),
    ('french', 16), ('fr', 16),
    ('swahili', 17), ('sw', 17),
    ('kinyarwanda', 18), ('rw', 18), ('rw rw', 18),
    ('malay', 20), ('ms', 20),
    ('gondi', 21), ('gon', 21), ('koitur', 21),
    ('indonesian', 22), ('id', 22)
ON CONFLICT (alias) DO NOTHING;
