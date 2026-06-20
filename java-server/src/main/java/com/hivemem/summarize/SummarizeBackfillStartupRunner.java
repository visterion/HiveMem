package com.hivemem.summarize;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hivemem.summarize.enabled", havingValue = "true")
public class SummarizeBackfillStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SummarizeBackfillStartupRunner.class);

    private final DSLContext dsl;
    private final SummarizerProperties props;

    public SummarizeBackfillStartupRunner(DSLContext dsl, SummarizerProperties props) {
        this.dsl = dsl;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        int updated = dsl.execute(
                "UPDATE cells SET tags = array_append(COALESCE(tags, '{}'::text[]), 'needs_summary') "
                + "WHERE embedding IS NULL "
                + "AND length(content) > ? "
                + "AND status = 'committed' "
                + "AND valid_until IS NULL "
                // COALESCE so a (legacy/raw-inserted) NULL tags row is treated as empty and still
                // self-heals, instead of being silently skipped by `!= ALL(NULL)` (which is NULL).
                + "AND 'needs_summary' != ALL(COALESCE(tags, '{}'::text[]))",
                props.getSummaryThresholdChars());
        if (updated > 0) {
            log.info("Tagged {} cells (NULL embedding, long content) with needs_summary for backfill", updated);
        }
    }
}
