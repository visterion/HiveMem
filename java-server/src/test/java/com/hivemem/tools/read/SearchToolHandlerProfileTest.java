package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/** Which six weights the {@code search} handler hands to {@link ReadToolService#search}. */
class SearchToolHandlerProfileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal("writer-1", AuthRole.WRITER);

    private final ReadToolService readToolService = Mockito.mock(ReadToolService.class);
    private final SearchToolHandler handler = new SearchToolHandler(readToolService);

    private double[] weightsFor(String argumentsJson) throws Exception {
        handler.call(PRINCIPAL, MAPPER.readTree(argumentsJson));
        ArgumentCaptor<Double> w = ArgumentCaptor.forClass(Double.class);
        verify(readToolService).search(anyString(), anyInt(), isNull(), isNull(), isNull(), any(),
                w.capture(), w.capture(), w.capture(), w.capture(), w.capture(), w.capture(),
                any(), any(), any(), anyBoolean());
        return w.getAllValues().stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Test
    void noProfileUsesBalanced() throws Exception {
        assertThat(weightsFor("{\"query\": \"q\"}"))
                .containsExactly(0.20, 0.25, 0.15, 0.15, 0.15, 0.10);
    }

    @Test
    void balancedProfileNameUsesTheSameWeights() throws Exception {
        assertThat(weightsFor("{\"query\": \"q\", \"profile\": \"balanced\"}"))
                .containsExactly(0.20, 0.25, 0.15, 0.15, 0.15, 0.10);
    }

    @Test
    void partialOverrideTakesTheRestFromTheProfile() throws Exception {
        assertThat(weightsFor("{\"query\": \"q\", \"weight_keyword\": 0.45}"))
                .containsExactly(0.20, 0.45, 0.15, 0.15, 0.15, 0.10);
    }

    @Test
    void semanticProfileIsUnchanged() throws Exception {
        assertThat(weightsFor("{\"query\": \"q\", \"profile\": \"semantic\"}"))
                .containsExactly(0.55, 0.10, 0.05, 0.10, 0.10, 0.10);
    }

    @Test
    void explicitWeightsArePassedThrough() throws Exception {
        assertThat(weightsFor("{\"query\": \"q\", \"weight_semantic\": 0.3, \"weight_keyword\": 0.2, "
                + "\"weight_recency\": 0.1, \"weight_importance\": 0.25, \"weight_popularity\": 0.05, "
                + "\"weight_graph_proximity\": 0.07}"))
                .containsExactly(0.3, 0.2, 0.1, 0.25, 0.05, 0.07);
    }
}
