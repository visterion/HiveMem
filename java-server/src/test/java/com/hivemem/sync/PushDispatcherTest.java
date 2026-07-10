package com.hivemem.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushDispatcherTest {

    @Mock SyncPeerRepository peerRepository;
    @Mock SyncOpsRepository syncOpsRepository;
    @Mock PeerClient peerClient;
    @Mock InstanceConfig instanceConfig;

    @InjectMocks PushDispatcher dispatcher;

    @Test
    void dispatchSendsOpToAllPeers() {
        UUID opId = UUID.randomUUID();
        UUID myId = UUID.randomUUID();
        UUID peer1 = UUID.randomUUID();
        UUID peer2 = UUID.randomUUID();

        when(instanceConfig.instanceId()).thenReturn(myId);
        OpDto op = new OpDto(1L, opId, "add_cell",
                new ObjectMapper().createObjectNode(),
                OffsetDateTime.now());
        when(syncOpsRepository.findOpById(opId)).thenReturn(op);
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peer1, "http://peer1:8421", 0L, "tok1"),
                new SyncPeer(peer2, "http://peer2:8421", 0L, "tok2")));

        dispatcher.dispatchSync(opId);

        verify(peerClient).pushOps("http://peer1:8421", myId, List.of(op), "tok1");
        verify(peerClient).pushOps("http://peer2:8421", myId, List.of(op), "tok2");
    }

    @Test
    void dispatchDoesNotThrowWhenPeerFails() {
        UUID opId = UUID.randomUUID();
        UUID myId = UUID.randomUUID();
        UUID peerUuid = UUID.randomUUID();

        when(instanceConfig.instanceId()).thenReturn(myId);
        OpDto op = new OpDto(1L, opId, "add_cell",
                new ObjectMapper().createObjectNode(),
                OffsetDateTime.now());
        when(syncOpsRepository.findOpById(opId)).thenReturn(op);
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peerUuid, "http://down:8421", 0L, "tok")));
        doThrow(new RuntimeException("conn refused"))
                .when(peerClient).pushOps(any(), any(), any(), any());

        // must not throw
        dispatcher.dispatchSync(opId);
    }

    @Test
    void dispatchSyncSkipsSelfPeerRow() {
        UUID opId = UUID.randomUUID();
        UUID myId = UUID.randomUUID();
        UUID otherPeer = UUID.randomUUID();

        when(instanceConfig.instanceId()).thenReturn(myId);
        OpDto op = new OpDto(1L, opId, "add_cell",
                new ObjectMapper().createObjectNode(),
                OffsetDateTime.now());
        when(syncOpsRepository.findOpById(opId)).thenReturn(op);
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(myId, "http://self:8421", 0L, "self-tok"),
                new SyncPeer(otherPeer, "http://peer:8421", 0L, "tok")));

        dispatcher.dispatchSync(opId);

        verify(peerClient, never()).pushOps(eq("http://self:8421"), any(), any(), any());
        verify(peerClient).pushOps("http://peer:8421", myId, List.of(op), "tok");
    }

    @Test
    void dispatchSkipsIfOpNotFound() {
        UUID opId = UUID.randomUUID();
        when(syncOpsRepository.findOpById(opId)).thenReturn(null);

        dispatcher.dispatchSync(opId);

        verify(peerClient, never()).pushOps(any(), any(), any(), any());
    }
}
