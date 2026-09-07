-- Local development fixture.
--
-- Deliberately NOT a Flyway migration. Migrations define the schema and run in every
-- environment including production; demo data does neither. Keeping them apart means a
-- migration history that is purely structural and can be read as a schema changelog.
--
-- Run with: make seed

INSERT INTO scheme (code, name, total_flats, applications_open_at, applications_close_at, status)
VALUES (
    'MHS-2026',
    'Municipal Housing Scheme 2026 - Phase I',
    600,
    TIMESTAMPTZ '2026-01-15 00:00:00+00',
    TIMESTAMPTZ '2026-12-31 23:59:59+00',
    'OPEN'
)
ON CONFLICT (code) DO NOTHING;
