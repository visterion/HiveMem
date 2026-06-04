# HiveMem 9.1.1

Patch release. Fixes a startup crash that only triggers with the consumption
pipeline actually enabled in production.

## Fix

- **Consumption watcher could not be constructed by Spring.** `ConsumptionWatcher`
  has two constructors — the Spring one `(props, service)` and a package-private
  test-only one `(props, service, Clock)`. With neither annotated, the container
  could not choose one and failed to start with `No default constructor found`
  once `hivemem.consumption.enabled=true`. Every test built the watcher by hand,
  so the bean was never wired in a real Spring context until enabled in
  production. Marked the Spring constructor `@Autowired`.

- **Regression test** (`ConsumptionWatcherWiringTest`) loads the watcher in an
  `ApplicationContextRunner` with the feature flag on, pinning its
  constructor-injectability so this class of wiring gap fails in CI, not prod.

## Upgrade

No schema changes (still at V0030). Drop-in over 9.1.0.
