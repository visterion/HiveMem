package com.hivemem.queen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup (when hivemem.queen.enabled=true) idempotently registers the Bee then the
 * Queen in Vistierie. Tolerant: if Vistierie is unreachable, logs a warning and lets the
 * application continue — the next boot heals the registration.
 */
@Component
public class VistierieAgentBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VistierieAgentBootstrap.class);

    private final QueenProperties props;
    private final VistierieAgentClient client;

    public VistierieAgentBootstrap(QueenProperties props, VistierieAgentClient client) {
        this.props = props;
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            log.info("Queen disabled (hivemem.queen.enabled=false) — skipping agent bootstrap");
            return;
        }
        AgentDefinitions defs = new AgentDefinitions(props);
        try {
            client.upsertAgent(AgentDefinitions.BEE_NAME, defs.isolatedCellBee());
            client.upsertAgent(AgentDefinitions.QUEEN_NAME, defs.queen());
            log.info("Registered Queen + Bee agents in Vistierie at {}", props.getVistierieBaseUrl());
        } catch (RuntimeException e) {
            log.warn("Vistierie agent bootstrap failed ({}); will retry on next start", e.toString());
        }
    }
}
