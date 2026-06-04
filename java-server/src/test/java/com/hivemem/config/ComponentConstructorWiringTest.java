package com.hivemem.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Regression guard for a bug class that bit twice in production: a bean with two constructors —
 * the Spring one and a package-private test-only one — and neither marked {@code @Autowired}. Spring
 * cannot choose a constructor, falls back to a non-existent no-arg one, and fails with
 * "No default constructor found". It only surfaces when the bean is actually instantiated — and for
 * {@code @ConditionalOnProperty} beans that is the first time the feature flag is true, i.e. in
 * production (ConsumptionWatcher when consumption.enabled=true, OcrService when ocr.enabled=true).
 *
 * This scans every @Service/@Component under com.hivemem and fails if any has multiple constructors,
 * none @Autowired, and no no-arg constructor — exactly the un-instantiable shape.
 */
class ComponentConstructorWiringTest {

    @Test
    void componentsWithMultipleConstructorsAreConstructorInjectable() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class)); // @Service is a @Component
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        List<String> problems = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.hivemem")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            Constructor<?>[] ctors = type.getDeclaredConstructors();
            if (ctors.length <= 1) continue;
            boolean anyAutowired = Arrays.stream(ctors).anyMatch(c -> c.isAnnotationPresent(Autowired.class));
            boolean hasNoArg = Arrays.stream(ctors).anyMatch(c -> c.getParameterCount() == 0);
            if (!anyAutowired && !hasNoArg) {
                problems.add(type.getName() + " has " + ctors.length
                        + " constructors, none @Autowired and no no-arg — Spring cannot instantiate it");
            }
        }

        assertThat(problems)
                .as("each @Service/@Component with multiple constructors must mark one @Autowired "
                        + "(or provide a no-arg constructor)")
                .isEmpty();
    }
}
