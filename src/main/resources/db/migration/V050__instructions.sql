-- V050: Standing instructions ("always show the item description on line items").
--
-- A user proposes an instruction with /knowledge; an ADMIN or DOMAIN_OWNER reviews it. Only APPROVED
-- instructions ever reach a prompt. At question time Zevra selects the few that are relevant (see
-- InstructionPromptService): instructions flagged always_apply (a small, capped set), plus the
-- nearest approved instructions to the question by embedding similarity above a floor. The prompt
-- therefore stays the same size however many instructions exist.
--
-- Tenant-scoped like the other tenant tables: unqualified name, no search_path override, so it is
-- created in `public` (kept empty) and in every tenant schema.
--
-- embedding is filled when an instruction is approved and is NULL while it is PENDING/ARCHIVED.
-- Exact scan, no ANN index: a tenant has tens to hundreds of instructions.

CREATE TABLE IF NOT EXISTS nexus_instruction (
    instruction_id  VARCHAR(120)  PRIMARY KEY,
    instruction_text TEXT         NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'ARCHIVED')),
    always_apply    BOOLEAN       NOT NULL DEFAULT FALSE,
    embedding       vector(1536),
    submitted_by    VARCHAR(255),
    reviewed_by     VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    reviewed_at     TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_instruction_status ON nexus_instruction (status);
