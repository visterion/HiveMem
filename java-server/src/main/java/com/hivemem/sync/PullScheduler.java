package com.hivemem.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PullScheduler {

    private static final Logger log = LoggerFactory.getLogger(PullScheduler.class);

    private final SyncPeerRepository peerRepository;
    private final PeerClient peerClient;
    private final OpReplayer opReplayer;
    private final InstanceConfig instanceConfig;

    public PullScheduler(SyncPeerRepository peerRepository, PeerClient peerClient,
                         OpReplayer opReplayer, InstanceConfig instanceConfig) {
        this.peerRepository = peerRepository;
        this.peerClient = peerClient;
        this.opReplayer = opReplayer;
        this.instanceConfig = instanceConfig;
    }

    @Scheduled(fixedDelayString = "${hivemem.sync.pull-interval-ms:60000}")
    public void pullAll() {
        for (SyncPeer peer : peerRepository.findAllPeers()) {
            if (peer.peerUuid().equals(instanceConfig.instanceId())) continue; // skip self
            try {
                pullFromPeer(peer);
            } catch (Exception e) {
                log.warn("Pull failed peer={}", peer.peerUrl(), e);
            }
        }
    }

    private void pullFromPeer(SyncPeer peer) {
        List<OpDto> ops = peerClient.fetchOps(peer.peerUrl(), peer.lastSeenSeq(), peer.outboundToken());
        if (ops.isEmpty()) return;

        long maxReplayed = peer.lastSeenSeq();
        for (OpDto op : ops) {
            OpReplayer.ReplayResult result = opReplayer.replay(peer.peerUuid(), op);
            if (result == OpReplayer.ReplayResult.FAILED) {
                // Ops are sequential: do NOT advance last_seen_seq past a failed op — it would be
                // dropped forever. Stop here; this op and everything after it is retried on the
                // next pull.
                log.warn("Op replay failed op={} type={} from peer={} — stopping at seq {}, will retry next pull",
                        op.opId(), op.opType(), peer.peerUrl(), maxReplayed);
                break;
            }
            if (result == OpReplayer.ReplayResult.UNKNOWN_OP) {
                log.warn("Skipping unknown op_type={} op={} from peer={}",
                        op.opType(), op.opId(), peer.peerUrl());
            }
            maxReplayed = op.seq();
        }
        if (maxReplayed > peer.lastSeenSeq()) {
            peerRepository.updateLastSeenSeq(peer.peerUuid(), maxReplayed);
        }
    }
}
