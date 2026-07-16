package com.hivemem.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ToolPermissionServiceRealmTest {
    private final ToolPermissionService svc = new ToolPermissionService();
    private final ObjectMapper mapper = new ObjectMapper();

    private AuthPrincipal scoped() {
        return new AuthPrincipal("t", AuthRole.WRITER, null,
                List.of("dracul-research", "dracul"), List.of("dracul-research"));
    }
    private AuthPrincipal unscoped() { return new AuthPrincipal("u", AuthRole.WRITER); }
    private JsonNode args(String json) throws Exception { return mapper.readTree(json); }

    // ---- WRITE ----
    @Test void addCellOwnRealmAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"realm\":\"dracul-research\"}"))).isEmpty();
    }
    @Test void addCellForeignRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"realm\":\"personal\"}"))).isPresent();
    }
    @Test void addCellReadOnlyRealmDenied_readIsNotWrite() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"realm\":\"dracul\"}"))).isPresent();
    }
    @Test void kgAddAllowedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "kg_add", args("{}"))).isEmpty();
    }
    @Test void reclassifyDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "reclassify", args("{\"realm\":\"dracul-research\"}"))).isPresent();
    }
    @Test void reviseCellDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "revise_cell", args("{}"))).isPresent();
    }

    // ---- READ ----
    @Test void searchAllowedButFiltered() throws Exception {
        assertThat(svc.realmDenial(scoped(), "search", args("{\"query\":\"x\"}"))).isEmpty();
    }
    @Test void traverseDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "traverse", args("{}"))).isPresent();
    }
    @Test void dataQualityReportDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "data_quality_report", args("{}"))).isPresent();
    }
    @Test void readingListDeniedForScoped() throws Exception {
        assertThat(svc.realmDenial(scoped(), "reading_list", args("{}"))).isPresent();
    }
    @Test void searchKgGlobalAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "search_kg", args("{}"))).isEmpty();
    }
    @Test void listDrilldownForeignRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "list", args("{\"realm\":\"personal\"}"))).isPresent();
    }
    @Test void listDrilldownOwnRealmAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "list", args("{\"realm\":\"dracul\"}"))).isEmpty();
    }

    // ---- filterReadResponse ----
    @Test void searchResponseDropsForeignRows() throws Exception {
        JsonNode result = mapper.readTree(
                "[{\"id\":\"1\",\"realm\":\"dracul-research\"},{\"id\":\"2\",\"realm\":\"personal\"}]");
        JsonNode filtered = svc.filterReadResponse(scoped(), "search", result);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("realm").asText()).isEqualTo("dracul-research");
    }
    @Test void getCellForeignRealmBecomesEmpty() throws Exception {
        JsonNode result = mapper.readTree("{\"id\":\"9\",\"realm\":\"work\"}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "get_cell", result);
        assertThat(filtered.isNull() || filtered.isEmpty() || filtered.isMissingNode()).isTrue();
    }
    @Test void statusRealmsFilteredToReadRealms() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"cells\":10,\"facts\":5,\"realms\":[{\"value\":\"dracul-research\"},{\"value\":\"personal\"}]}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "status", result);
        assertThat(filtered.get("realms")).hasSize(1);
        assertThat(filtered.get("realms").get(0).get("value").asText()).isEqualTo("dracul-research");
    }

    // ---- BACKWARD COMPAT (NULL/NULL = no-op) ----
    @Test void unscopedNeverDenied() throws Exception {
        for (String t : List.of("add_cell","reclassify","traverse","data_quality_report","list")) {
            assertThat(svc.realmDenial(unscoped(), t, args("{\"realm\":\"personal\"}"))).isEmpty();
        }
    }
    @Test void unscopedResponseUnchanged() throws Exception {
        JsonNode result = mapper.readTree("[{\"id\":\"2\",\"realm\":\"personal\"}]");
        assertThat(svc.filterReadResponse(unscoped(), "search", result)).isEqualTo(result);
    }
    @Test void unscopedArgsUnchanged() throws Exception {
        JsonNode a = args("{\"query\":\"x\"}");
        assertThat(svc.rewriteReadArgs(unscoped(), "search", a)).isEqualTo(a);
    }
}
