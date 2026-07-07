-- Semantic fact search: nullable embedding on facts (dimension enforced at runtime like cells).
ALTER TABLE facts ADD COLUMN IF NOT EXISTS embedding vector;

-- active_facts froze its column list at creation; recreate so embedding flows through.
DROP VIEW IF EXISTS active_facts;
CREATE VIEW active_facts AS
    SELECT * FROM facts
    WHERE (valid_until IS NULL OR valid_until > now()) AND status = 'committed';
