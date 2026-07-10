package com.hivemem.backup;

import org.jooq.DSLContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.util.List;

public class EmptinessCheck {

    /**
     * User-data tables that decide whether the target counts as "empty". Deliberately excludes
     * boot-seeded singleton tables (instance_identity, identity, agents): the restore process
     * itself boots the app against the target and seeds those, and a previously-booted-but-
     * data-empty target must still be restorable without --force.
     */
    private static final List<String> DATA_TABLES = List.of(
            "cells", "attachments", "facts", "tunnels",
            "references_", "blueprints", "agent_diary", "ops_log");

    private final DSLContext dsl;
    private final S3Client s3;
    private final String bucket;

    public EmptinessCheck(DSLContext dsl, S3Client s3, String bucket) {
        this.dsl = dsl;
        this.s3 = s3;
        this.bucket = bucket;
    }

    public boolean dbEmpty() {
        for (String table : DATA_TABLES) {
            Long count = dsl.fetchOne("SELECT count(*) FROM " + table).get(0, Long.class);
            if (count != null && count > 0) {
                return false;
            }
        }
        return true;
    }

    public boolean bucketEmpty() {
        var resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).maxKeys(1).build());
        return resp.contents().isEmpty();
    }
}
