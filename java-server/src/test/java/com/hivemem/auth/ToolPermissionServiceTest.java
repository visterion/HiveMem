package com.hivemem.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolPermissionServiceTest {

    private final ToolPermissionService svc = new ToolPermissionService();

    @Test
    void queenRunsToolsAreAdminOnly() {
        assertThat(svc.isAllowed(AuthRole.ADMIN, "queen_runs")).isTrue();
        assertThat(svc.isAllowed(AuthRole.ADMIN, "queen_run_detail")).isTrue();
        assertThat(svc.isAllowed(AuthRole.WRITER, "queen_runs")).isFalse();
        assertThat(svc.isAllowed(AuthRole.READER, "queen_run_detail")).isFalse();
    }
}
