# Status Log

## T1.4 Refund policy for cancellations > 12 hours before class

### What was found
The member reported: *"I cancelled my class 20 hours before it started and only got half my money back. Please fix this."*

Investigation revealed two root causes:
1. **Incorrect threshold in `BookingService.java`**: The cancellation check was implemented as `Duration.between(now, slot.startsAt()).toHours() >= 24`. Any cancellation made between 12 and 24 hours prior to class start (such as 20 hours prior) was erroneously classified as a late cancellation (`early = false`) and awarded only 50% refund instead of the 90% early cancellation policy.
2. **Truncation and double precision in `RefundCalculator.java`**: Refunds were calculated using `double` and truncated via `(long)(refund * 100) / 100.0` instead of rounding half-up to the nearest paisa. This caused 1-paisa discrepancies (e.g. 135.45 * 0.9 = 121.905 was truncated to 121.90 instead of 121.91).

### What was done
1. **Standardized on the documented business policy**:
   - Cancellations made at least 12 hours before class start (`>= 12` hours) receive **90% refund**.
   - Cancellations made less than 12 hours before start receive **50% refund**.
   - Cancellations after class start remain rejected with `409 CONFLICT`.
2. **Fixed `BookingService.java`**:
   - Injected `CentreClock` to calculate time relative to the centre's configured timezone (`Asia/Kolkata`).
   - Delegates early qualification check to `RefundCalculator.isEarly(now, classStart, zone)`.
3. **Fixed `RefundCalculator.java`**:
   - Added `isEarly(Instant now, Instant classStart, ZoneId zone)` comparing against `Duration.ofHours(12)`.
   - Switched money calculation to `BigDecimal` with `RoundingMode.HALF_UP` to 2 decimal places (paise precision).
4. **Enhanced Test Coverage**:
   - `RefundCalculatorTest.java`: Added unit tests verifying exact 12-hour boundary (qualifies for early), 12 hours minus 1 second (late), 20 hours before (early), 6 hours before (late), and `BigDecimal` half-up rounding precision to nearest paisa.
   - `BookingFlowTest.java`: Added integration tests verifying cancellation 20 hours before class yields 90% refund, cancellation under 12 hours yields 50% refund, and class already started yields `409 CONFLICT`.

### How we know it is right
- Ran focused unit test suite: `mvn -pl core "-Dtest=RefundCalculatorTest" test` -> 4 tests passed, 0 failures.
- Ran integration test suite: `mvn -pl core "-Dtest=RefundCalculatorTest,BookingFlowTest" test` -> 11 tests passed, 0 failures.
- Ran full core test suite: `mvn -pl core test` -> 13 tests passed, 0 failures.

## T1.7 Refund paisa mismatch — monthly total does not match gateway

### What was found
The admin refund report (`GET /api/admin/reports/refunds?month=2026-10`) returned a total of **₹6853.30** while the payment gateway settlement CSV (`docs/gateway-refunds-2026-10.csv`) showed **₹6853.50** — a difference of ₹0.20 across 20 of 30 rows.

Three root causes:

1. **Truncation rounding in V3 migration data**: The October history migration (`V3__october_history.sql`) hardcoded refund amounts that were computed with the old `(long)(refund * 100) / 100.0` truncation formula instead of `BigDecimal` HALF_UP rounding. For example, booking 900001 paid ₹135.45 with a 90% early refund: `135.45 × 0.9 = 121.905` was truncated to ₹121.90 but the gateway actually settled ₹121.91. Twenty rows were each off by ₹0.01 (always under-paid by 1 paisa).

2. **`Math.floor` on the total in `AdminController.java`**: Line 65 used `Math.floor(total * 100) / 100` to format the sum, which truncates downward instead of rounding HALF_UP. Even after fixing the individual amounts, this would still introduce off-by-one-paisa errors on certain sums.

3. **UTC month boundaries instead of centre timezone**: The report's SQL query used `ZoneOffset.UTC` for the month range parameters, but all payment `created_at` timestamps in V3 are stored in `+05:30` (Asia/Kolkata). This could cause payments near month boundaries to be included or excluded incorrectly.

4. **Missing admin authorization check**: The endpoint used `current.require()` (any signed-in member) instead of `current.requireAdmin()`, allowing non-admin members to access the financial reconciliation report.

### Row-by-row comparison (before fix)

| Booking | V3 DB amount | Gateway amount | Difference |
|---------|-------------|----------------|------------|
| 900001  | 121.90      | 121.91         | +0.01 ✗    |
| 900002  | 299.99      | 300.00         | +0.01 ✗    |
| 900003  | 225.00      | 225.00         | 0.00 ✓     |
| 900004  | 539.10      | 539.10         | 0.00 ✓     |
| 900005  | 116.95      | 116.96         | +0.01 ✗    |
| 900006  | 67.72       | 67.73          | +0.01 ✗    |
| (pattern repeats 5× for bookings 900007–900030) | | | |
| **TOTAL** | **6853.30** | **6853.50** | **+0.20** |

20 rows off by 1 paisa each. All caused by truncation instead of HALF_UP. Affected booking amounts: ₹135.45×0.9, ₹333.33×0.9, ₹129.95×0.9, ₹135.45×0.5.

### What was done

1. **Created `V4__fix_october_refunds.sql`**: A Flyway migration that UPDATEs the 20 incorrect refund amounts in the `payments` table to match the gateway's correct HALF_UP values: 121.90→121.91, 299.99→300.00, 116.95→116.96, 67.72→67.73.

2. **Fixed `AdminController.java`**:
   - Injected `CentreClock` and replaced `ZoneOffset.UTC` with `centreClock.zone()` for month boundary calculation.
   - Replaced `Math.floor(total * 100) / 100` with `BigDecimal` accumulation using `RoundingMode.HALF_UP` to 2 decimal places.
   - Changed `current.require()` to `current.requireAdmin()` on the refund report endpoint.

3. **Updated `MyBookings.jsx`**: Changed "24 hours" to "12 hours" to match the corrected T1.4 refund policy.

4. **Added `AdminRefundReportTest.java`** (3 new tests):
   - `octoberRefundTotalMatchesGateway()`: Hits the endpoint as admin, asserts 30 refunds and total = 6853.50.
   - `refundReportRequiresAdmin()`: Regular member gets 403 Forbidden.
   - `refundReportRequiresSignIn()`: No auth gets 401 Unauthorized.

### After fix

| Metric | Before | After |
|--------|--------|-------|
| Report total | ₹6853.30 | ₹6853.50 |
| Gateway total | ₹6853.50 | ₹6853.50 |
| Rows differing | 20 / 30 | **0 / 30** |

### How we know it is right
- Ran focused test: `mvn -pl core "-Dtest=AdminRefundReportTest" test` → 3 tests passed, 0 failures.
- Ran full core test suite: `mvn -pl core test` → 16 tests passed (3 AdminRefundReportTest + 2 AuthTest + 7 BookingFlowTest + 4 RefundCalculatorTest), 0 failures.

## T2. Waitlist module

### What was built
A complete waitlist module in `modules/waitlist/` following `CONTRACT.md` and using `modules/reminders/` as the template. When a slot is full, members can join a waitlist; when a booking is cancelled, the first waitlisted member is auto-booked and notified.

### Endpoints

| Method & Path | Who | Behaviour |
|---|---|---|
| `POST /api/modules/waitlist/slots/{slotId}/join` | member | Join waitlist → `200 {"position": N}`. Returns `409` if slot has free seats, member already on list, or already holds a booking |
| `DELETE /api/modules/waitlist/slots/{slotId}/me` | member | Leave waitlist → `204` |
| `GET /api/modules/waitlist/slots/{slotId}` | member | View waitlist → `200 {"size": N, "myPosition": N|null, "entries": [...]}`. Other members' names hidden as "Member N" |
| `GET /api/modules/waitlist/admin/slots/{slotId}` | admin | View waitlist with real names → `200 {"size": N, "entries": [...]}` |
| `DELETE /api/modules/waitlist/admin/slots/{slotId}` | admin | Clear waitlist → `204` |

### Key design decisions

1. **Idempotent event handling**: `waitlist_processed_events` table records each `BookingCancelled.eventId`. Duplicate deliveries (at-least-once contract) are safely skipped.
2. **One cancellation → at most one booking**: After recording the event, we poll the first waitlisted member and attempt `Bookings.book()`. If that fails (FULL, SLOT_STARTED, etc.), we skip to the next. Only one member is booked per cancellation.
3. **Privacy**: Member view shows own name but "Member N" for others. Admin view shows real names and member IDs.
4. **Money through core only**: Auto-booking uses `Bookings.book()` which charges the member; the module never creates bookings or payments directly.
5. **Compiles only against `contract/`**: No imports from `com.slotbook.core` in module source code.

### Files created

- `modules/waitlist/src/main/resources/db/modules/V200_1__waitlist.sql` — Tables: `waitlist_entries`, `waitlist_processed_events`
- `modules/waitlist/src/main/java/.../WaitlistRepository.java` — CRUD + idempotency + poll-first
- `modules/waitlist/src/main/java/.../WaitlistController.java` — All 5 endpoints
- `modules/waitlist/src/main/java/.../WaitlistListener.java` — `@EventListener` for `BookingCancelled`
- `modules/waitlist/src/test/java/.../WaitlistExtraTest.java` — 7 extra tests (duplicate delivery, empty waitlist, double join, already booked, privacy, auth checks)

### How we know it is right
- Ran `mvnw -pl modules/waitlist -am verify` → BUILD SUCCESS
- WaitlistContractTest: 5 tests passed, 0 failures
- WaitlistExtraTest: 7 tests passed, 0 failures
- Core tests: 16 tests passed, 0 failures
- Total across reactor: 28 tests, 0 failures

## T1.1 Timezone / day boundary for 5:00 AM classes and Today tab

### What was found
The member reported: *"I booked Monday's 5:00 AM yoga, and My Bookings lists it under Sunday. And the Today tab never shows the 5:00 AM batch, but it does show tomorrow's."*

Root causes:
1. **`BookingController.java:64`**: Derived booking date using `slot.startsAt().atZone(clock.getZone()).toLocalDate()`. Since `clock` is UTC (`Clock.systemUTC()`), a 5:00 AM IST Monday class (`2026-11-16T05:00:00+05:30` = `2026-11-15T23:30:00Z`) converted to Sunday (`2026-11-15`).
2. **`SlotController.java:40-47`**: Generated start/end boundaries for `GET /api/slots?date=...` using `clock.getZone()` (UTC) and `LocalDate.now(clock)`. For `date=2026-11-16`, start was `2026-11-16T00:00:00Z` (05:30 AM IST). The 5:00 AM class at `23:30:00Z` Sunday was excluded from Monday's results and fell into Sunday's window (`2026-11-15T00:00:00Z` to `2026-11-16T00:00:00Z`).
3. **`MeController.java:40-48`**: Calculated monthly plan usage using `firstOfMonth.atStartOfDay().atOffset(ZoneOffset.UTC)`. Any 5:00 AM IST class on the 1st of a month (`23:30Z` previous day) was excluded from that month's count.
4. **`web/src/api.js:localDateString`**: Used browser's local date `d.getFullYear()`, `d.getMonth()`, `d.getDate()`. If browser is in UTC or a negative offset, "Today" computed the wrong day.
5. **`web/src/pages/Classes.jsx`**: Grouped classes by day using `new Date(slot.startsAt).toDateString()` (browser local zone instead of centre zone).
6. **`web/src/pages/MyBookings.jsx`**: `dayHeading` constructed a local Date that could shift across midnight in negative UTC offsets.

### What was done
1. **`BookingController.java`**: Injected `CentreClock` and updated line 64 to use `centreClock.zone()`, correctly producing Monday's date for 5:00 AM IST classes.
2. **`SlotController.java`**: Injected `CentreClock` and updated `from`/`to` calculations and `start`/`end` timestamps to use `centreClock.zone()`. Now a query for `date=YYYY-MM-DD` spans `00:00:00+05:30` to `23:59:59+05:30`, including the 5:00 AM batch.
3. **`MeController.java`**: Injected `CentreClock` and updated `firstOfMonth` and start/end parameters to use `centreClock.zone()`.
4. **`web/src/api.js`**: Updated `localDateString()` to use `Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' })`.
5. **`web/src/pages/Classes.jsx`**: Updated `addDays` to operate via `Asia/Kolkata` date math, and updated day grouping to format using `Intl.DateTimeFormat` with `timeZone: 'Asia/Kolkata'`.
6. **`web/src/pages/MyBookings.jsx`**: Updated `dayHeading` to format in `Asia/Kolkata` with a UTC noon anchor to prevent browser offset shifts.
7. **Added `TimezoneDateTest.java`**:
   - `monday5amSlotAppearsUnderMondayInMyBookings`: Verifies 5:00 AM IST Monday class returns Monday date (`2026-11-16`).
   - `monday5amSlotAppearsInTodayQueryForMonday`: Verifies `GET /api/slots?date=Monday` includes 5:00 AM IST slot, and `date=Sunday` excludes it.
   - `firstOfMonth5amSlotCountsInMonthlyPlanUsage`: Verifies 5:00 AM IST class on 1st of the month counts in monthly plan usage.

### How we know it is right
- Ran `mvn -pl core "-Dtest=TimezoneDateTest" test` → 3 tests passed, 0 failures.
- Ran full test suite `mvn test` → 33 tests passed, 0 failures.

## T1.2 Notification timestamps show seconds

### What was found
The member reported: *"The times on the Notifications page look messy, with seconds and everything."*

`web/src/pages/Notifications.jsx:23` displayed timestamps via `new Date(n.createdAt).toLocaleString('en-IN')`, which rendered full seconds (e.g. `20/11/2026, 5:00:00 am`) and used the browser's local timezone.

### What was done
- Updated `web/src/pages/Notifications.jsx` with `formatNotificationTime(isoString)` using `Intl.DateTimeFormat` configured with:
  - `timeZone: 'Asia/Kolkata'`
  - `day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', hour12: true`
  - Output format: `"20 Nov, 5:00 AM"` without seconds.
- Kept the backend storage (`Instant` in `timestamptz`) unchanged; modified the display layer only.
