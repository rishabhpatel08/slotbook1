# Corrections Log

T1
What the AI did: Maven wrapper failed to run because `maven-wrapper.properties` was missing from `.mvn/wrapper/`.
What I did: Found `maven-wrapper.properties.txt` in `.mvn/wrapper/` and copied it to `maven-wrapper.properties`.

T1
What the AI did: Inspected `RefundCalculator.java:18` which implemented `(long) (refund * 100) / 100.0`.
What I did: Replaced floating-point truncation with `BigDecimal` arithmetic scaled to 2 decimal places using `RoundingMode.HALF_UP`.

T1
What the AI did: Inspected `BookingService.java:131` which checked `Duration.between(now, slot.startsAt()).toHours() >= 24`.
What I did: Corrected threshold to 12 hours using `RefundCalculator.isEarly(now, slot.startsAt(), zone)` using `CentreClock.zone()` and verified boundary conditions.

T1
What the AI did: Replaced `untilStart` logic with `RefundCalculator.isEarly(now, slot.startsAt(), zone)` using `ZoneId zone = centreClock.zone()`, but omitted `import java.time.ZoneId;`.
What I did: Added `import java.time.ZoneId;` to `core/src/main/java/com/slotbook/core/bookings/BookingService.java` and re-ran tests successfully.

T1
What the AI did: T1.4 fixed the backend threshold from 24h to 12h in `RefundCalculator` and `BookingService`, but did not update the frontend confirmation dialog.
What I did: Updated the text to "12 hours". Grepped the entire repo for other "24 hour" mentions — only hits were in `STATUS.md` and `CORRECTIONS.md` documenting the old bug (intentional).

T1
What the AI did: Analysed `V3__october_history.sql` and found 20 of 30 refund payment amounts were computed with `(long)(refund * 100) / 100.0` (truncation) instead of BigDecimal HALF_UP rounding.
What I did: Created `V4__fix_october_refunds.sql` to UPDATE the 20 rows to their correct HALF_UP values. Did not modify V3 (already applied in production).

T1
What the AI did: Inspected `AdminController.java` and found three additional bugs: `Math.floor(total * 100) / 100` truncated the sum downward, `ZoneOffset.UTC` was used for month boundaries instead of the centre's timezone (Asia/Kolkata), and `current.require()` allowed non-admin members to access the financial report.
What I did: Injected `CentreClock`, used `centreClock.zone()` for month boundaries, replaced `Math.floor` with `BigDecimal` HALF_UP accumulation, and changed `require()` to `requireAdmin()`.

T1
What the AI did: Created a test slot with hardcoded timestamp `2026-11-15T23:30:00Z` and called `host.book(member, slotId)`.
What I did: Changed the test to compute the upcoming Monday dynamically using `LocalDate.now(kolkata).with(TemporalAdjusters.next(DayOfWeek.MONDAY))` at 5:00 AM IST, ensuring the slot is always in the future. For historical plan usage testing, inserted the confirmed booking directly via JDBC.

T1
What the AI did: Attempted to create a partial unique index on `bookings (slot_id, member_id) WHERE status = 'CONFIRMED'`.
What I did: Added an UPDATE statement in `V5__prevent_overbooking.sql` to cancel duplicate confirmed bookings, keeping only the earliest (`id = min(id)`), before applying the unique index.

T1
What the AI did: Proposed returning `Map.of("plan", plan != null ? plan.name() : null, ...)` when handling members without a plan.
What I did: Used `LinkedHashMap` to construct the response map, allowing `plan: null` while preserving key ordering.

T1
What the AI did: Defined a nested static `@TestConfiguration` class in `AssistantTest` with a fake LLM client, expecting Spring Boot to load it automatically.
What I did: Added `@Import(AssistantTest.Config.class)` to `AssistantTest` to register the fake LLM client bean in the test context.
