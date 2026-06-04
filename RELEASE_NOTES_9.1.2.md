# HiveMem 9.1.2

Patch release. Fixes the same Spring-wiring bug class as 9.1.1, this time in the OCR
service, and adds a guard so it cannot recur in any component.

## Fix

- **OcrService could not be constructed by Spring.** Like `ConsumptionWatcher` (9.1.1),
  `OcrService` has two constructors — the Spring one and a package-private test-only one —
  with neither marked `@Autowired`. With `hivemem.ocr.enabled=true` the context failed to
  start (`No default constructor found`). OCR shipped default-off and was never wired in a
  real Spring context until enabled in production. Marked the Spring constructor
  `@Autowired`.

- **Regression guard** (`ComponentConstructorWiringTest`): scans every `@Service`/`@Component`
  under `com.hivemem` and fails if any has multiple constructors, none `@Autowired`, and no
  no-arg constructor — the exact un-instantiable shape. This catches the whole bug class in
  CI for any future bean, not just the two known offenders.

## Upgrade

No schema changes (still at V0030). Drop-in over 9.1.1. Enables turning on
`hivemem.ocr.enabled=true` so scanned (image-only) documents are OCR'd into searchable text.
