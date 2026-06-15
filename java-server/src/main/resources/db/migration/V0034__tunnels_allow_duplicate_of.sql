-- Allow the 'duplicate_of' relation on tunnels.
-- Content-based scan dedup (DocumentDedupService) links a discarded re-scan to the original cell
-- via a 'duplicate_of' tunnel. The original tunnels.relation CHECK (V0002) only permitted
-- related_to/builds_on/contradicts/refines, so the insert silently failed under the dedup's
-- best-effort catch — making the discard a no-op. Extend the constraint to include 'duplicate_of'.
ALTER TABLE tunnels DROP CONSTRAINT IF EXISTS tunnels_relation_check;
ALTER TABLE tunnels
    ADD CONSTRAINT tunnels_relation_check
        CHECK (relation IN ('related_to','builds_on','contradicts','refines','duplicate_of'));
