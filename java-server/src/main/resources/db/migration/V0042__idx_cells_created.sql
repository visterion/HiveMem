-- Nearly every listing query orders by created_at DESC (list, list_documents,
-- list_media, list_cell_ids, stream snapshot, data-quality samples); index it.
CREATE INDEX IF NOT EXISTS idx_cells_created ON cells (created_at DESC);
