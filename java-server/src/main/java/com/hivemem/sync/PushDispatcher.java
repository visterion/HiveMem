package com.hivemem.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

@Component
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    /** Published for each appended op; delivered AFTER the surrounding transaction commits. */
    public record OpLoggedEvent(UUID opId) {}

    private final SyncPeerRepository peerRepository;
    private final SyncOpsRepository syncOpsRepository;
    private final PeerClient peerClient;
    private final InstanceConfig instanceConfig;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public PushDispatcher(SyncPeerRepository peerRepository, SyncOpsRepository syncOpsRepository,
                          PeerClient peerClient, InstanceConfig instanceConfig,
                          ApplicationEventPublisher eventPublisher) {
        this.peerRepository = peerRepository;
        this.syncOpsRepository = syncOpsRepository;
        this.peerClient = peerClient;
        this.instanceConfig = instanceConfig;
        this.eventPublisher = eventPublisher;
    }

    /** Test seam: a publisher-less dispatcher pushes synchronously on {@link #dispatch}. */
    public PushDispatcher(SyncPeerRepository peerRepository, SyncOpsRepository syncOpsRepository,
                          PeerClient peerClient, InstanceConfig instanceConfig) {
        this(peerRepository, syncOpsRepository, peerClient, instanceConfig, null);
    }

    /**
     * Schedule a push of the given op to all peers. Callers invoke this from inside the write
     * transaction that appended the op, so the actual dispatch must wait for the commit —
     * otherwise the async worker re-reads {@code ops_log} before the row is visible and drops
     * the push ("op not found"). The event listener below runs AFTER_COMMIT ({@code
     * fallbackExecution = true} covers non-transactional callers).
     */
    public void dispatch(UUID opId) {
        if (eventPublisher == null) {
            dispatchSync(opId);
            return;
        }
        eventPublisher.publishEvent(new OpLoggedEvent(opId));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOpLogged(OpLoggedEvent event) {
        dispatchSync(event.opId());
    }

    void dispatchSync(UUID opId) {
        OpDto op = syncOpsRepository.findOpById(opId);
        if (op == null) {
            log.warn("PushDispatcher: op not found op_id={}", opId);
            return;
        }
        UUID myId = instanceConfig.instanceId();
        for (SyncPeer peer : peerRepository.findAllPeers()) {
            if (peer.peerUuid().equals(myId)) continue; // never push to ourselves
            try {
                peerClient.pushOps(peer.peerUrl(), myId, List.of(op), peer.outboundToken());
            } catch (Exception e) {
                log.warn("Push failed peer={} op_id={}", peer.peerUrl(), opId, e);
            }
        }
    }
}
