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
    @Test void addCellMissingRealmDenied_noNullRealmEscape() throws Exception {
        // I1: an omitted realm must NOT default at the write path (would persist a null-realm
        // cell escaping write_realms) — a scoped write must name an explicit realm.
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"content\":\"x\"}"))).isPresent();
    }
    @Test void addCellBlankRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "add_cell", args("{\"content\":\"x\",\"realm\":\"  \"}"))).isPresent();
    }
    @Test void updateBlueprintMissingRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "update_blueprint", args("{}"))).isPresent();
    }
    @Test void uploadAttachmentMissingRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "upload_attachment", args("{}"))).isPresent();
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
        JsonNode filtered = svc.filterReadResponse(scoped(), "search", args("{}"), result);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("realm").asText()).isEqualTo("dracul-research");
    }
    @Test void getCellForeignRealmBecomesEmpty() throws Exception {
        JsonNode result = mapper.readTree("{\"id\":\"9\",\"realm\":\"work\"}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "get_cell", args("{}"), result);
        assertThat(filtered.isNull() || filtered.isEmpty() || filtered.isMissingNode()).isTrue();
    }
    @Test void statusRealmsFilteredToReadRealms() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"cells\":10,\"facts\":5,\"realms\":[{\"value\":\"dracul-research\"},{\"value\":\"personal\"}]}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "status", args("{}"), result);
        assertThat(filtered.get("realms")).hasSize(1);
        assertThat(filtered.get("realms").get(0).get("value").asText()).isEqualTo("dracul-research");
    }

    // ---- C1: list realm-enumeration filter ----
    @Test void listRealmEnumerationDropsForeignRealms() throws Exception {
        JsonNode result = mapper.readTree(
                "[{\"value\":\"dracul-research\",\"label\":\"dracul-research\",\"cell_count\":3},"
                + "{\"value\":\"dracul\",\"label\":\"dracul\",\"cell_count\":1},"
                + "{\"value\":\"personal\",\"label\":\"personal\",\"cell_count\":9},"
                + "{\"value\":\"work\",\"label\":\"work\",\"cell_count\":2}]");
        JsonNode filtered = svc.filterReadResponse(scoped(), "list", args("{}"), result);
        assertThat(filtered).hasSize(2);
        for (JsonNode row : filtered) {
            assertThat(row.get("value").asText()).isIn("dracul-research", "dracul");
        }
    }
    @Test void listDrilldownResponseNotFiltered_signalsAreNotRealms() throws Exception {
        // list realm=dracul → signals whose `value` are signal names, must survive untouched.
        JsonNode result = mapper.readTree(
                "[{\"value\":\"facts\",\"label\":\"facts\",\"cell_count\":3},"
                + "{\"value\":\"events\",\"label\":\"events\",\"cell_count\":1}]");
        JsonNode filtered = svc.filterReadResponse(scoped(), "list",
                args("{\"realm\":\"dracul\"}"), result);
        assertThat(filtered).hasSize(2);
    }

    // ---- C1: facet_count realm-bucket filter + precheck ----
    @Test void facetCountRealmBucketsDropForeignRealms() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"realm\":[{\"value\":\"dracul-research\",\"count\":3},"
                + "{\"value\":\"personal\",\"count\":9}],"
                + "\"signal\":[{\"value\":\"facts\",\"count\":5}]}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "facet_count", args("{}"), result);
        assertThat(filtered.get("realm")).hasSize(1);
        assertThat(filtered.get("realm").get(0).get("value").asText()).isEqualTo("dracul-research");
        assertThat(filtered.get("signal")).hasSize(1); // signal buckets untouched
    }
    @Test void facetCountForeignWhereRealmInDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "facet_count",
                args("{\"fields\":[\"realm\"],\"where\":{\"realm_in\":[\"personal\"]}}"))).isPresent();
    }
    @Test void facetCountVisibleWhereRealmInAllowed() throws Exception {
        assertThat(svc.realmDenial(scoped(), "facet_count",
                args("{\"fields\":[\"realm\"],\"where\":{\"realm_in\":[\"dracul\"]}}"))).isEmpty();
    }

    // ---- C1: list_cell_ids row filter + precheck ----
    @Test void listCellIdsDropsForeignAndNullRealmRows() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"ids\":[{\"id\":\"1\",\"realm\":\"dracul\"},"
                + "{\"id\":\"2\",\"realm\":\"personal\"},"
                + "{\"id\":\"3\",\"realm\":null}],\"total\":3}");
        JsonNode filtered = svc.filterReadResponse(scoped(), "list_cell_ids", args("{}"), result);
        assertThat(filtered.get("ids")).hasSize(1);
        assertThat(filtered.get("ids").get(0).get("realm").asText()).isEqualTo("dracul");
    }
    @Test void listCellIdsForeignWhereRealmDenied() throws Exception {
        assertThat(svc.realmDenial(scoped(), "list_cell_ids",
                args("{\"where\":{\"realm\":\"personal\"}}"))).isPresent();
    }

    // ---- I2 + C1: rewriteReadArgs injects read_realms ----
    @Test void rewriteInjectsReadRealmsForSearchWithoutRealm() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search", args("{\"query\":\"x\"}"));
        JsonNode realmIn = out.path("where").path("realm_in");
        assertThat(realmIn.isArray()).isTrue();
        java.util.List<String> values = new java.util.ArrayList<>();
        realmIn.forEach(n -> values.add(n.asText()));
        assertThat(values).containsExactlyInAnyOrder("dracul-research", "dracul");
        assertThat(out.path("query").asText()).isEqualTo("x"); // query stays top-level
    }
    @Test void rewriteFoldsFlatFilterParamsIntoWhere() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "search",
                args("{\"query\":\"x\",\"signal\":\"facts\"}"));
        assertThat(out.has("signal")).isFalse(); // flat param folded away
        assertThat(out.path("where").path("signal").asText()).isEqualTo("facts");
        assertThat(out.path("where").path("realm_in").isArray()).isTrue();
    }
    @Test void rewriteLeavesCallerNamedRealmUntouched() throws Exception {
        JsonNode in = args("{\"where\":{\"realm_in\":[\"dracul\"]}}");
        JsonNode out = svc.rewriteReadArgs(scoped(), "list_cell_ids", in);
        JsonNode realmIn = out.path("where").path("realm_in");
        assertThat(realmIn).hasSize(1);
        assertThat(realmIn.get(0).asText()).isEqualTo("dracul");
    }
    @Test void rewriteInjectsForListCellIdsWithoutWhere() throws Exception {
        JsonNode out = svc.rewriteReadArgs(scoped(), "list_cell_ids", args("{}"));
        assertThat(out.path("where").path("realm_in").isArray()).isTrue();
        assertThat(out.path("where").path("realm_in")).hasSize(2);
    }
    @Test void rewriteNoOpForListTool() throws Exception {
        JsonNode in = args("{}");
        assertThat(svc.rewriteReadArgs(scoped(), "list", in)).isEqualTo(in);
    }

    // ---- ONE-SIDED SCOPE (read-only-scoped and write-only-scoped) ----
    private AuthPrincipal readOnlyScoped() {
        return new AuthPrincipal("t", AuthRole.WRITER, null, List.of("dracul-research"), null);
    }
    private AuthPrincipal writeOnlyScoped() {
        return new AuthPrincipal("t", AuthRole.WRITER, null, null, List.of("dracul-research"));
    }

    @Test void readOnlyScopedReclassifyNotDenied_writesUnrestricted() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "reclassify", args("{\"realm\":\"dracul-research\"}"))).isEmpty();
    }
    @Test void readOnlyScopedAddCellForeignRealmNotDenied_writesUnrestricted() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "add_cell", args("{\"realm\":\"personal\"}"))).isEmpty();
    }
    @Test void readOnlyScopedReviseCellNotDenied_writesUnrestricted() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "revise_cell", args("{}"))).isEmpty();
    }
    @Test void readOnlyScopedTraverseStillDenied_readsAreFiltered() throws Exception {
        assertThat(svc.realmDenial(readOnlyScoped(), "traverse", args("{}"))).isPresent();
    }
    @Test void readOnlyScopedSearchResponseDropsForeignRows_readsAreFiltered() throws Exception {
        JsonNode result = mapper.readTree(
                "[{\"id\":\"1\",\"realm\":\"dracul-research\"},{\"id\":\"2\",\"realm\":\"personal\"}]");
        JsonNode filtered = svc.filterReadResponse(readOnlyScoped(), "search", args("{}"), result);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("realm").asText()).isEqualTo("dracul-research");
    }

    @Test void writeOnlyScopedAddCellForeignRealmDenied() throws Exception {
        assertThat(svc.realmDenial(writeOnlyScoped(), "add_cell", args("{\"realm\":\"personal\"}"))).isPresent();
    }
    @Test void writeOnlyScopedTraverseNotDenied_readsAreUnrestricted() throws Exception {
        assertThat(svc.realmDenial(writeOnlyScoped(), "traverse", args("{}"))).isEmpty();
    }
    @Test void writeOnlyScopedSearchResponseUnchanged_readsAreUnrestricted() throws Exception {
        JsonNode result = mapper.readTree("[{\"id\":\"2\",\"realm\":\"personal\"}]");
        assertThat(svc.filterReadResponse(writeOnlyScoped(), "search", args("{}"), result)).isEqualTo(result);
    }

    // ---- BACKWARD COMPAT (NULL/NULL = no-op) ----
    @Test void unscopedNeverDenied() throws Exception {
        for (String t : List.of("add_cell","reclassify","traverse","data_quality_report","list")) {
            assertThat(svc.realmDenial(unscoped(), t, args("{\"realm\":\"personal\"}"))).isEmpty();
        }
    }
    @Test void unscopedResponseUnchanged() throws Exception {
        JsonNode result = mapper.readTree("[{\"id\":\"2\",\"realm\":\"personal\"}]");
        assertThat(svc.filterReadResponse(unscoped(), "search", args("{}"), result)).isEqualTo(result);
    }
    @Test void unscopedArgsUnchanged() throws Exception {
        JsonNode a = args("{\"query\":\"x\"}");
        assertThat(svc.rewriteReadArgs(unscoped(), "search", a)).isEqualTo(a);
    }
}
