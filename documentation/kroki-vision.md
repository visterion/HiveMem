# Kroki-Render + Vision-Bildbeschreibung (Phase 1)

Async-Anreicherung nach Attachment-Upload:

- **Kroki:** Diagramm-Quelltexte (Mermaid, PlantUML, Graphviz, D2) werden zu PNG-Thumbnails gerendert und in SeaweedFS unter `s3_key_thumbnail` abgelegt.
- **Vision:** Bilder (image/jpeg|png|gif|webp) erhalten eine Claude-Haiku-Bildbeschreibung; der Cell-Content wird von „Dateiname" auf KI-Beschreibung revidiert.

## Pipeline

```
Cell mit Tag kroki_pending oder vision_pending
  → AttachmentEnrichmentService
      Kroki:   KrokiClient.render → SeaweedFs.uploadBytes → AttachmentRepository.updateThumbnailKey
      Vision:  VisionBudgetTracker.canSpend → VisionClient.describe → WriteToolService.reviseCell
                                            → vision_usage += cost
      → Cell-Tag entfernen

Backfill (stündlich) für offene Pending-Tags.
```

## Konfiguration

```yaml
hivemem:
  attachment:
    kroki-url: ${HIVEMEM_KROKI_URL:}                # leer = Kroki deaktiviert
    kroki-timeout-seconds: 10
    kroki-backfill-interval: PT1H                   # ISO-8601 (env: HIVEMEM_KROKI_BACKFILL_INTERVAL)
    anthropic-api-key: ${ANTHROPIC_API_KEY:}        # leer = Vision deaktiviert
    vision-timeout-seconds: 30
    vision-model: claude-haiku-4-5-20251001
    vision-daily-budget-usd: 1.0
    vision-backfill-interval: PT1H                  # ISO-8601 (env: HIVEMEM_VISION_BACKFILL_INTERVAL)
    vision-max-input-bytes: 5242880                 # 5 MB
```

## Schema

V0028 fügt `vision_usage` (Daily-Cost-Cap-Tracker) hinzu. Thumbnails landen in der bestehenden `attachments.s3_key_thumbnail`-Spalte (V0023).

## Tags

| Tag | Bedeutung |
|---|---|
| `kroki_pending` | Render läuft / wartet auf Backfill |
| `kroki_failed` | Kroki hat Diagramm-Quelltext zurückgewiesen — kein Auto-Retry |
| `vision_pending` | Vision-Call wartet (Event oder Backfill) |
| `vision_throttled` | 429 — wird beim nächsten Backfill neu versucht |
| `vision_failed` | Bild zu groß oder unsupported — kein Auto-Retry |

## MIME-Type-Erkennung

Multipart-Uploads liefern oft `text/plain` für `.mmd`/`.puml`/`.dot`/`.gv`/`.d2`-Dateien. `MimeTypeResolver` re-mapped den MIME basierend auf der Dateiendung, bevor der Parser-Dispatch greift.

## Bekannte Einschränkungen

- 4 Kroki-Formate (Mermaid, PlantUML, Graphviz, D2). Weitere lassen sich durch reines Erweitern von `KrokiClient.MIME_TO_FORMAT` ergänzen.
- Vision-Call > `vision-max-input-bytes` (default 5 MB) wird als `vision_failed` markiert, kein Resize-Fallback.
- Kroki braucht externen Service (z.B. `yuzutech/kroki` Docker-Image lokal).
- `kroki_failed` und `vision_failed` werden nie automatisch entfernt — manuelle Bereinigung nötig.
