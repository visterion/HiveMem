package com.hivemem.sync;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sync")
public class SyncController {

    private final SyncOpsRepository syncOpsRepository;
    private final OpReplayer opReplayer;

    public SyncController(SyncOpsRepository syncOpsRepository, OpReplayer opReplayer) {
        this.syncOpsRepository = syncOpsRepository;
        this.opReplayer = opReplayer;
    }

    @GetMapping("/ops")
    public Map<String, Object> getOps(@RequestParam(defaultValue = "0") long since) {
        List<OpDto> ops = syncOpsRepository.findOpsAfter(since);
        long maxSeq = ops.isEmpty() ? since : ops.getLast().seq();
        return Map.of("ops", ops, "max_seq", maxSeq);
    }

    @PostMapping("/ops")
    public Map<String, Object> receiveOps(@RequestBody SyncPushRequest request) {
        OpReplayer.BatchResult result = opReplayer.replayAll(request.sourcePeer(), request.ops());
        return Map.of("replayed", result.replayed(), "skipped", result.skipped(),
                "failed", result.failed());
    }
}
