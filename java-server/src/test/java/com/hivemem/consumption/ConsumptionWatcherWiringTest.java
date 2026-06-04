package com.hivemem.consumption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression: ConsumptionWatcher has two constructors — the Spring one (props, service) and a
 * package-private test-only one (props, service, Clock). Without {@code @Autowired} on the Spring
 * constructor, the container cannot choose one and fails to start with
 * "No default constructor found" once hivemem.consumption.enabled=true. Every other test builds the
 * watcher by hand, so this only ever surfaced in a real Spring context (i.e. in production). This
 * test pins the bean's constructor-injectability.
 */
class ConsumptionWatcherWiringTest {

    @Test
    void watcherIsConstructorInjectableInSpringContext() {
        new ApplicationContextRunner()
                .withPropertyValues("hivemem.consumption.enabled=true")
                .withBean(ConsumptionProperties.class)
                .withBean(ConsumptionService.class, () -> mock(ConsumptionService.class))
                .withBean("consumptionExecutor", Executor.class, () -> (Executor) Runnable::run)
                .withBean(ConsumptionWatcher.class)
                .run(ctx -> assertThat(ctx)
                        .hasNotFailed()
                        .hasSingleBean(ConsumptionWatcher.class));
    }
}
