package com.hivemem.consumption;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionConfig {

    /**
     * Bounded worker pool so multi-page OCR runs off the @Scheduled poll thread without unbounded
     * concurrency against Vistierie/embeddings. CallerRunsPolicy applies backpressure (the submitting
     * poll thread runs the task) instead of dropping work, so nothing is lost under a burst.
     */
    @Bean(name = "consumptionExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor consumptionExecutor(ConsumptionProperties props) {
        int n = Math.max(1, props.getWorkerThreads());
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                n, n, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(8, n * 8)),
                r -> {
                    Thread t = new Thread(r, "consumption-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }
}
