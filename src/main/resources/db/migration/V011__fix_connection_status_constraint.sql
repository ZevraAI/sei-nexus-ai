-- V011: Add ARCHIVED to nexus_connection status check constraint.
--
-- V010 defined status CHECK (status IN ('ACTIVE','INACTIVE','ERROR')).
-- ConnectionRepository.archive() sets status = 'ARCHIVED', which violates
-- that constraint on schemas where V010 has already been applied.

ALTER TABLE nexus_connection
    DROP CONSTRAINT IF EXISTS nexus_connection_status_check;

ALTER TABLE nexus_connection
    ADD CONSTRAINT nexus_connection_status_check
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ERROR', 'ARCHIVED'));
