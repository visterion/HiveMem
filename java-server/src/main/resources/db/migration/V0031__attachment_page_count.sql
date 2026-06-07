-- Adds page_count to attachments for the document explorer (SP-C1).
ALTER TABLE attachments ADD COLUMN page_count INTEGER;
