# Corrections Log

## Maven wrapper configuration filename
What the AI did: Maven wrapper failed to run because `maven-wrapper.properties` was missing from `.mvn/wrapper/`.
How I noticed: Ran `.\mvnw.cmd -pl core -am test` and observed `Cannot find path 'F:\h1\.mvn\wrapper\maven-wrapper.properties'`.
What I did: Found `maven-wrapper.properties.txt` in `.mvn/wrapper/` and copied it to `maven-wrapper.properties`.

## Truncation vs Half-Up Rounding in RefundCalculator
What the AI did: Inspected `RefundCalculator.java:18` which implemented `(long) (refund * 100) / 100.0`.
How I noticed: Cross-checked `docs/gateway-refunds-2026-10.csv` and noticed gateway rounded paisa half-up (e.g. 135.45 * 0.9 = 121.905 was paid as 121.91, and 333.33 * 0.9 = 299.997 was paid as 300.00), whereas `(long) (refund * 100) / 100.0` truncated to 121.90 and 299.99.
What I did: Replaced floating-point truncation with `BigDecimal` arithmetic scaled to 2 decimal places using `RoundingMode.HALF_UP`.

## Early cancellation threshold in BookingService
What the AI did: Inspected `BookingService.java:131` which checked `Duration.between(now, slot.startsAt()).toHours() >= 24`.
How I noticed: Ticket T1.4 stated: *"I cancelled my class 20 hours before it started and only got half my money back."* The 24-hour check caused members cancelling 20 hours in advance to receive 50% instead of the 90% early refund.
What I did: Corrected threshold to 12 hours using `RefundCalculator.isEarly(now, slot.startsAt(), zone)` using `CentreClock.zone()` and verified boundary conditions.

## Missing ZoneId import in BookingService
What the AI did: Replaced `untilStart` logic with `RefundCalculator.isEarly(now, slot.startsAt(), zone)` using `ZoneId zone = centreClock.zone()`, but omitted `import java.time.ZoneId;`.
How I noticed: Ran `mvn -pl core "-Dtest=RefundCalculatorTest,BookingFlowTest" test` and the test suite failed with `java.lang.Error: Unresolved compilation problem: ZoneId cannot be resolved to a type`.
What I did: Added `import java.time.ZoneId;` to `core/src/main/java/com/slotbook/core/bookings/BookingService.java` and re-ran tests successfully.

## MyBookings.jsx still said "24 hours" after T1.4 fix
What the AI did: T1.4 fixed the backend threshold from 24h to 12h in `RefundCalculator` and `BookingService`, but did not update the frontend confirmation dialog.
How I noticed: The user pointed out that `web/src/pages/MyBookings.jsx:22` still read "at least 24 hours before the class".
What I did: Updated the text to "12 hours". Grepped the entire repo for other "24 hour" mentions — only hits were in `STATUS.md` and `CORRECTIONS.md` documenting the old bug (intentional).

## V3 migration refund amounts used truncation instead of HALF_UP
What the AI did: Analysed `V3__october_history.sql` and found 20 of 30 refund payment amounts were computed with `(long)(refund * 100) / 100.0` (truncation) instead of BigDecimal HALF_UP rounding.
How I noticed: Row-by-row comparison against `docs/gateway-refunds-2026-10.csv` showed 20 rows each off by 1 paisa (e.g. 121.90 vs gateway 121.91), totalling ₹0.20 difference.
What I did: Created `V4__fix_october_refunds.sql` to UPDATE the 20 rows to their correct HALF_UP values. Did not modify V3 (already applied in production).

## AdminController used Math.floor and UTC for refund report
What the AI did: Inspected `AdminController.java` and found three additional bugs: `Math.floor(total * 100) / 100` truncated the sum downward, `ZoneOffset.UTC` was used for month boundaries instead of the centre's timezone (Asia/Kolkata), and `current.require()` allowed non-admin members to access the financial report.
How I noticed: Cross-referencing the report endpoint code against the gateway CSV and the app's `slotbook.centre-zone: Asia/Kolkata` configuration.
What I did: Injected `CentreClock`, used `centreClock.zone()` for month boundaries, replaced `Math.floor` with `BigDecimal` HALF_UP accumulation, and changed `require()` to `requireAdmin()`.

## Test slot in the past rejected with SLOT_STARTED in TimezoneDateTest
What the AI did: Created a test slot with hardcoded timestamp `2026-11-15T23:30:00Z` and called `host.book(member, slotId)`.
How I noticed: The test failed with `java.lang.IllegalStateException: Could not book: SLOT_STARTED` because `BookingService.java:66` rejects booking slots in the past (`!slot.startsAt().isAfter(clock.instant())`), and `2026-11-15` was in the past relative to the system clock.
What I did: Changed the test to compute the upcoming Monday dynamically using `LocalDate.now(kolkata).with(TemporalAdjusters.next(DayOfWeek.MONDAY))` at 5:00 AM IST, ensuring the slot is always in the future. For historical plan usage testing, inserted the confirmed booking directly via JDBC.

## Duplicate seed bookings broke partial unique constraint in V5 migration
What the AI did: Attempted to create a partial unique index on `bookings (slot_id, member_id) WHERE status = 'CONFIRMED'`.
How I noticed: Inspected `V2__seed.sql` and found explicit duplicate bookings intentionally seeded (chess club members 5 and 9 duplicated across lines 112-116, and spoken English duplicate booking across lines 122-125). Creating the constraint without resolving these would cause Flyway migration to fail on startup.
What I did: Added an UPDATE statement in `V5__prevent_overbooking.sql` to cancel duplicate confirmed bookings, keeping only the earliest (`id = min(id)`), before applying the unique index.

## NullPointerException with Java Map.of when plan is null in MeController
What the AI did: Proposed returning `Map.of("plan", plan != null ? plan.name() : null, ...)` when handling members without a plan.
How I noticed: In Java, `Map.of()` rejects null values and throws `NullPointerException` at runtime.
What I did: Used `LinkedHashMap` to construct the response map, allowing `plan: null` while preserving key ordering.
