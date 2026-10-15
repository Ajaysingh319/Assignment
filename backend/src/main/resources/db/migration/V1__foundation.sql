-- Flyway is wired. Domain tables are added in later migrations.
-- This statement is intentionally lightweight so the application can start
-- against an empty local PostgreSQL database.
CREATE TABLE IF NOT EXISTS schema_foundation (
    installed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
