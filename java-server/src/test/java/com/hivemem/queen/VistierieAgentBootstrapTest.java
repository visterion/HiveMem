package com.hivemem.queen;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VistierieAgentBootstrapTest {

    private QueenProperties enabledProps() {
        QueenProperties p = new QueenProperties();
        p.setEnabled(true);
        p.setWebhookToken("wt");
        p.setCompletionWebhookToken("cwt");
        return p;
    }

    @Test
    void registersBeeBeforeQueen() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        new VistierieAgentBootstrap(enabledProps(), client).run(null);
        InOrder order = inOrder(client);
        order.verify(client).upsertAgent(eq("isolated-cell-bee"), any());
        order.verify(client).upsertAgent(eq("queen"), any());
    }

    @Test
    void disabledDoesNothing() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        QueenProperties disabled = new QueenProperties(); // enabled=false
        new VistierieAgentBootstrap(disabled, client).run(null);
        verifyNoInteractions(client);
    }

    @Test
    void registrationFailureDoesNotThrow() {
        VistierieAgentClient client = mock(VistierieAgentClient.class);
        doThrow(new RuntimeException("connect refused"))
                .when(client).upsertAgent(eq("isolated-cell-bee"), any());
        // must not throw
        new VistierieAgentBootstrap(enabledProps(), client).run(null);
        // queen never attempted because bee failed; bootstrap swallowed the error
        verify(client, never()).upsertAgent(eq("queen"), any());
    }
}
