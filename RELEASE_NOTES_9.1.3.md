# HiveMem 9.1.3

Patch release. Makes OCR auto-orient rotated/upside-down scans.

## Fix

- **TesseractRunner now uses `--psm 1` (was `--psm 3`).** PSM 1 runs automatic page
  segmentation *with* OSD (orientation & script detection), so pages fed rotated or
  upside-down are auto-oriented before recognition. PSM 3 skipped orientation detection and
  produced scrambled, 180°-rotated text on a real upside-down scan. The `osd` traineddata
  ships in the image. Benefits both the OCR backfill and the consumption separation path
  (same runner).

## Upgrade

No schema changes (still at V0030). Drop-in over 9.1.2.
