# Backup + Portability

HiveMem can export an entire instance into a single tar.gz archive and restore
it on another host. This is the operational guarantee behind the mission promise
"your knowledge stays yours, forever, locally."

## What is in a backup

- All Postgres data (cells, attachments metadata, facts, tunnels, ops_log,
  sync_peers, applied_ops, oauth + api tokens, hooks).
- All SeaweedFS attachments (binary blobs).
- A `manifest.json` with schema version, instance identity, Flyway version, and
  counts.

The archive layout:

```
hivemem-backup-<instance_id>-<utc-timestamp>.tar.gz
├── manifest.json
├── postgres.sql.gz       (gzipped pg_dump --data-only output)
└── attachments/
    └── <key>             (one entry per S3 object)
```

Schema is intentionally NOT in the dump — it is reconstructed by Flyway on the
target. This keeps backups smaller and decouples backup from schema evolution.

## Prerequisites

The container image already includes `pg_dump` and `psql` (postgresql-client-17).
For host-installed setups, install them via your package manager. The CI pipeline
installs `postgresql-client-17` from the PGDG repository.

## Export (CLI)

```
java -jar app.jar --spring.profiles.active=backup \
    backup export --out /var/lib/hivemem/exports/backup.tar.gz
```

The HTTP server is disabled in the `backup` profile; only the export runs and
the JVM exits.

## Restore (CLI)

There are two restore modes — pick one based on whether the source instance
is shutting down or staying alive.

### Disaster recovery / hardware migration — `--mode=move` (default)

Use this when the **source instance is being permanently shut down** and the
new host should take over its identity in the sync network.

```
java -jar app.jar --spring.profiles.active=backup \
    backup restore --in backup.tar.gz --mode=move
```

The CLI prompts for confirmation. Pass `--yes` for non-interactive scripts.

The target adopts the source's `instance_id`, `ops_log`, `sync_peers`, and
`applied_ops`. **Two running peers with the same `instance_id` corrupt the
sync network** — make sure the source is permanently down.

### Clone / fork — `--mode=clone`

Use this when the source stays alive and you want an **independent peer** with
the same data (e.g. test environment seeded from production).

```
java -jar app.jar --spring.profiles.active=backup \
    backup restore --in backup.tar.gz --mode=clone
```

The target gets a freshly generated `instance_id`. `ops_log`, `sync_peers`,
`applied_ops`, and `sync_conflicts` are truncated. Data tables (cells,
attachments, facts, tunnels) are restored as-is. The `OpLogBackfillRunner`
rebuilds the op log on the next start of the restored instance.

### Forcing a restore over an existing instance

By default, restore refuses if:
- The target database is non-empty (cells or attachments table has rows).
- The target S3 bucket is non-empty.
- (`--mode=move` only) The target's `instance_identity` differs from the
  archive's manifest.

Pass `--force` to truncate target tables and the S3 bucket before importing:

```
backup restore --in backup.tar.gz --mode=move --force --yes
```

`--force` does NOT bypass schema mismatches — those refuse unconditionally.

### Schema mismatches

If the archive's Flyway version differs from the target DB's version, the
restore refuses with a clear message — even with `--force`. Migrate the target
DB to the matching version first, or use a matching archive.

### Post-restore verification

After import, the restore compares row counts (cells, attachments, facts,
tunnels) and the attachment object count against what `manifest.json`
declared.

- **Row-count mismatches are logged as a WARNING, not a failure.** The
  manifest's counts are read from the app's live DB connection *before*
  `pg_dump` takes its own later snapshot — backing up an instance that is
  still being written to (a "live" backup) routinely produces a manifest
  whose counts differ slightly from what the dump actually contains. That is
  expected and does not indicate a corrupt archive; the restore completes
  normally.
- **The attachment object count (S3 blobs actually restored vs. what the
  manifest lists) remains a hard failure.** Unlike DB row counts, S3 objects
  are written once and are not subject to that race, so a mismatch there
  means the archive is genuinely truncated or corrupt.
- **`--mode=clone` always rotates the target's identity**, even when
  verification logs a warning or ultimately fails — the identity rotation
  runs before verification specifically so a live-backup count mismatch (or a
  corrupt archive) can never leave a clone restore holding the *source's*
  `instance_id`/`ops_log`, which would otherwise cause split-brain in the
  sync network.

## Encryption

Backups are not encrypted at rest by default. Built-in encryption is on the
roadmap (see Phase 3 of the design). For production use, encrypt the archive
externally:

```
gpg --encrypt --recipient operator@example.com backup.tar.gz
```

## Off-site copy

After every export, copy the archive to off-site storage (S3, Backblaze,
Google Drive, an rsync target, ...). HiveMem does not enforce a destination —
the operator picks based on threat model and infrastructure.

## Disaster-recovery drill

We recommend running a full `backup restore --mode=move` against a throwaway
test instance every six months. This is the only way to know your backup
actually works under realistic conditions.
