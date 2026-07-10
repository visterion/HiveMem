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
class PullSchedulerTest {

    @Mock SyncPeerRepository peerRepository;
    @Mock PeerClient peerClient;
    @Mock OpReplayer opReplayer;
    @Mock InstanceConfig instanceConfig;

    @InjectMocks PullScheduler scheduler;

    @Test
    void pullAllCallsFetchForEachPeer() {
        UUID peerUuid = UUID.randomUUID();
        when(instanceConfig.instanceId()).thenReturn(UUID.randomUUID());
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peerUuid, "http://peer:8421", 10L, "tok")));
        when(peerClient.fetchOps("http://peer:8421", 10L, "tok")).thenReturn(List.of());

        scheduler.pullAll();

        verify(peerClient).fetchOps("http://peer:8421", 10L, "tok");
        verify(peerRepository, never()).updateLastSeenSeq(any(), anyLong());
    }

    @Test
    void pullAllUpdatesLastSeenSeqAfterSuccessfulReplay() {
        UUID peerUuid = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        var op = new OpDto(42L, opId, "add_cell",
                new ObjectMapper().createObjectNode(), OffsetDateTime.now());

        when(instanceConfig.instanceId()).thenReturn(UUID.randomUUID());
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peerUuid, "http://peer:8421", 0L, "tok")));
        when(peerClient.fetchOps("http://peer:8421", 0L, "tok")).thenReturn(List.of(op));
        when(opReplayer.replay(eq(peerUuid), eq(op))).thenReturn(OpReplayer.ReplayResult.REPLAYED);

        scheduler.pullAll();

        verify(peerRepository).updateLastSeenSeq(peerUuid, 42L);
    }

    @Test
    void pullAllSkipsPeerIfFetchThrows() {
        UUID peerUuid = UUID.randomUUID();
        when(instanceConfig.instanceId()).thenReturn(UUID.randomUUID());
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peerUuid, "http://down:8421", 0L, "tok")));
        when(peerClient.fetchOps(any(), anyLong(), any())).thenThrow(new RuntimeException("down"));

        // must not throw
        scheduler.pullAll();

        verify(opReplayer, never()).replay(any(), any());
    }

    @Test
    void pullAllSkipsUnknownOpAndContinuesBatch() {
        UUID peerUuid = UUID.randomUUID();
        UUID opId1 = UUID.randomUUID();
        UUID opId2 = UUID.randomUUID();
        var op1 = new OpDto(10L, opId1, "unknown_type",
                new ObjectMapper().createObjectNode(), OffsetDateTime.now());
        var op2 = new OpDto(11L, opId2, "add_cell",
                new ObjectMapper().createObjectNode(), OffsetDateTime.now());

        when(instanceConfig.instanceId()).thenReturn(UUID.randomUUID());
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peerUuid, "http://peer:8421", 0L, "tok")));
        when(peerClient.fetchOps("http://peer:8421", 0L, "tok")).thenReturn(List.of(op1, op2));
        when(opReplayer.replay(eq(peerUuid), eq(op1))).thenReturn(OpReplayer.ReplayResult.UNKNOWN_OP);
        when(opReplayer.replay(eq(peerUuid), eq(op2))).thenReturn(OpReplayer.ReplayResult.REPLAYED);

        scheduler.pullAll();

        // op2 still replayed despite op1 being UNKNOWN_OP
        verify(opReplayer).replay(eq(peerUuid), eq(op2));
        // maxReplayed advances to op2's seq
        verify(peerRepository).updateLastSeenSeq(peerUuid, 11L);
    }

    @Test
    void pullAllStopsAdvancingSeqAtFirstFailedOp() {
        UUID peerUuid = UUID.randomUUID();
        var ok = new OpDto(10L, UUID.randomUUID(), "add_cell",
                new ObjectMapper().createObjectNode(), OffsetDateTime.now());
        var failing = new OpDto(11L, UUID.randomUUID(), "add_cell",
                new ObjectMapper().createObjectNode(), OffsetDateTime.now());
        var afterFailure = new OpDto(12L, UUID.randomUUID(), "add_cell",
                new ObjectMapper().createObjectNode(), OffsetDateTime.now());

        when(instanceConfig.instanceId()).thenReturn(UUID.randomUUID());
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peerUuid, "http://peer:8421", 0L, "tok")));
        when(peerClient.fetchOps("http://peer:8421", 0L, "tok"))
                .thenReturn(List.of(ok, failing, afterFailure));
        when(opReplayer.replay(eq(peerUuid), eq(ok))).thenReturn(OpReplayer.ReplayResult.REPLAYED);
        when(opReplayer.replay(eq(peerUuid), eq(failing))).thenReturn(OpReplayer.ReplayResult.FAILED);

        scheduler.pullAll();

        // seq stops at the last op BEFORE the failure — the failed op is retried next pull
        verify(peerRepository).updateLastSeenSeq(peerUuid, 10L);
        // ops after the failure are not replayed out of order
        verify(opReplayer, never()).replay(eq(peerUuid), eq(afterFailure));
    }

    @Test
    void pullAllDoesNotAdvanceSeqWhenFirstOpFails() {
        UUID peerUuid = UUID.randomUUID();
        var failing = new OpDto(5L, UUID.randomUUID(), "add_cell",
                new ObjectMapper().createObjectNode(), OffsetDateTime.now());

        when(instanceConfig.instanceId()).thenReturn(UUID.randomUUID());
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(peerUuid, "http://peer:8421", 4L, "tok")));
        when(peerClient.fetchOps("http://peer:8421", 4L, "tok")).thenReturn(List.of(failing));
        when(opReplayer.replay(eq(peerUuid), eq(failing))).thenReturn(OpReplayer.ReplayResult.FAILED);

        scheduler.pullAll();

        verify(peerRepository, never()).updateLastSeenSeq(any(), anyLong());
    }

    @Test
    void pullAllSkipsSelfPeer() {
        UUID myId = UUID.randomUUID();
        when(instanceConfig.instanceId()).thenReturn(myId);
        when(peerRepository.findAllPeers()).thenReturn(List.of(
                new SyncPeer(myId, "http://self:8421", 0L, "tok")));

        scheduler.pullAll();

        verify(peerClient, never()).fetchOps(any(), anyLong(), any());
    }
}
