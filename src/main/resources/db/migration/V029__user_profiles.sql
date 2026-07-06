-- V029: Supabase Auth user profile index
-- Single source of truth mapping email → tenant_schema + role for JWT-based auth.
-- Lives in the public schema so SupabaseAuthFilter can resolve tenant without prior context.

SET search_path = public;

CREATE TABLE IF NOT EXISTS nexus_user_profile (
    email         VARCHAR(255) PRIMARY KEY,
    tenant_schema VARCHAR(64)  NOT NULL REFERENCES nexus_tenant(schema_name) ON DELETE CASCADE,
    role          VARCHAR(40)  NOT NULL DEFAULT 'ANALYST'
                      CHECK (role IN ('ADMIN','ANALYST','DOMAIN_OWNER')),
    status        VARCHAR(40)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','INACTIVE','INVITED')),
    display_name  VARCHAR(255),
    invited_by    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_profile_tenant ON nexus_user_profile(tenant_schema);
CREATE INDEX IF NOT EXISTS idx_user_profile_status ON nexus_user_profile(status);

-- Migrate existing users from all active tenant schemas into the profile table.
-- Uses LOWER(email) for consistency — Supabase normalises emails to lowercase.
-- ON CONFLICT DO NOTHING makes this idempotent if re-run.
DO $$
DECLARE
    t   RECORD;
    sql TEXT;
BEGIN
    FOR t IN
        SELECT schema_name
        FROM public.nexus_tenant
        WHERE status = 'ACTIVE'
        ORDER BY schema_name
    LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = t.schema_name
              AND table_name   = 'nexus_user_account'
        ) THEN
            sql := format($s$
                INSERT INTO public.nexus_user_profile
                    (email, tenant_schema, role, status, display_name, created_at, updated_at)
                SELECT
                    LOWER(email),
                    %L  AS tenant_schema,
                    role,
                    status,
                    display_name,
                    created_at,
                    updated_at
                FROM %I.nexus_user_account
                ON CONFLICT (email) DO NOTHING
            $s$, t.schema_name, t.schema_name);
            EXECUTE sql;
        END IF;
    END LOOP;
END;
$$;
