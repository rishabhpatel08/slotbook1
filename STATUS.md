# Status Log

## T1 Overview: Front Desk Tickets

| Ticket | Status | What was found | What was done | How it was verified |
|---|---|---|---|---|
| **T1.1** | **Done** | Server ran in UTC so Monday 5:00 AM IST slot (`23:30Z` Sunday) mapped to Sunday in My Bookings and was omitted from Monday Today queries. | Injected `CentreClock` across `BookingController`, `SlotController`, `MeController`, and updated frontend `api.js`, `Classes.jsx`, and `MyBookings.jsx` to use `Asia/Kolkata`. | Verified with 3 new automated tests in `TimezoneDateTest.java` (all passed). |
| **T1.2** | **Done** | Notification timestamps in `web/src/pages/Notifications.jsx` displayed seconds via `.toLocaleString('en-IN')` in browser local time. | Formatted notification timestamps in `Asia/Kolkata` without seconds (e.g. `"20 Nov, 5:00 AM"`) in `web/src/pages/Notifications.jsx` without altering backend storage. | Verified by code inspection and frontend formatting verification. |
| **T1.3** | **Done** | Unlocked check-then-insert across 120ms gateway delay caused concurrent overbooking; no unique constraint on active bookings; attendance query duplicated members. | Added pessimistic slot row locking (`SELECT FOR UPDATE`) in `BookingRepository`/`BookingService`, deduplicated seed data and added partial unique index in `V5__prevent_overbooking.sql`, and added `DISTINCT ON (member_id)` in `AdminController.attendance`. | Verified with multi-threaded concurrency tests in `ConcurrentBookingTest.java` (reproduced failure before fix, passed after fix). |
| **T1.4** | **Done** | `BookingService` checked `>= 24` hours instead of 12 hours for 90% refund, and `RefundCalculator` truncated floats instead of rounding half-up. | Updated early threshold to 12 hours in `BookingService` and implemented `BigDecimal` arithmetic with `RoundingMode.HALF_UP` in `RefundCalculator`. | Verified with 4 unit tests in `RefundCalculatorTest.java` and integration tests in `BookingFlowTest.java` (all passed). |
| **T1.5** | **Done** | New members and seeded members 33–35 have `plan_id = null`, causing `members.findPlan(null).orElseThrow()` to throw `NoSuchElementException` (HTTP 500). | Added null check in `MemberRepository.findPlan()`, returned a safe map with `plan: null` and 0 usage in `MeController`, and added an empty-state card in `MyPlan.jsx`. | Verified with `NewMemberPlanTest.java` (reproduced HTTP 500 before fix, passed after fix). |
| **T1.6** | **Skipped** | Feature request for parents to book classes for children from their own account. | Architected and proposed a minimal design with a `dependents` table, `dependent_id` on bookings, caller ownership verification, and family plan pooling; implementation skipped pending user approval. | **Unverified (not implemented).** |
| **T1.7** | **Done** | October refund report was ₹6853.30 vs gateway ₹6853.50 due to 20 seed rows in `V3` truncated to nearest paisa, `Math.floor` in `AdminController`, and UTC month bounds. | Created Flyway migration `V4__fix_october_refunds.sql` to correct the 20 rows to HALF_UP values, updated `AdminController` to use `Asia/Kolkata` and `BigDecimal` sum, and enforced admin auth. | Verified with 3 tests in `AdminRefundReportTest.java` asserting exact match to gateway CSV (total ₹6853.50). |

---

## T1.1 Timezone / day boundary for 5:00 AM classes and Today tab
- **Status**: Done
- **What was found**: `BookingController:64`, `SlotController:40-47`, and `MeController:40-48` calculated dates using `clock.getZone()` (UTC) instead of `Asia/Kolkata`. A Monday 5:00 AM IST slot (`23:30Z` Sunday) mapped to Sunday in My Bookings, disappeared from the Today tab for Monday, and failed to count in monthly plan usage when falling on the 1st of a month. Frontend `api.js` and pages also grouped days using the browser's local timezone.
- **What was done**: Injected `CentreClock` into `BookingController`, `SlotController`, and `MeController` to anchor all date derivations to `Asia/Kolkata`. Updated frontend `api.js:localDateString()`, `Classes.jsx`, and `MyBookings.jsx` to format in `Asia/Kolkata`.
- **How it was verified**: Added `TimezoneDateTest.java` with 3 tests (`monday5amSlotAppearsUnderMondayInMyBookings`, `monday5amSlotAppearsInTodayQueryForMonday`, `firstOfMonth5amSlotCountsInMonthlyPlanUsage`). All 3 passed.

---

## T1.2 Notification timestamps show seconds
- **Status**: Done
- **What was found**: `web/src/pages/Notifications.jsx:23` rendered notifications via `new Date(n.createdAt).toLocaleString('en-IN')`, outputting seconds (e.g. `20/11/2026, 5:00:00 am`) in the user's local browser timezone.
- **What was done**: Implemented `formatNotificationTime()` in `web/src/pages/Notifications.jsx` using `Intl.DateTimeFormat` with `timeZone: 'Asia/Kolkata'` and no seconds, producing formats like `"20 Nov, 5:00 AM"`. Backend database timestamps (`timestamptz`) were kept intact.
- **How it was verified**: Verified by code inspection and frontend formatting verification.

---

## T1.3 Saturday chess club overbooking & duplicate attendance
- **Status**: Done
- **What was found**: `BookingService.book()` checked slot capacity and existing bookings without locking, across a simulated 120ms payment gateway delay, allowing race conditions under concurrent bookings. The database lacked a unique constraint on active bookings, and `V2__seed.sql` contained duplicate confirmed bookings for chess club (members 5 and 9) and spoken English. In addition, `AdminController.attendance` joined bookings directly to members without deduplication.
- **What was done**:
  1. Added `lockSlot(long slotId)` (`SELECT id FROM slots WHERE id = :id FOR UPDATE`) in `BookingRepository` and annotated `BookingService.book()` with `@Transactional`.
  2. Created `V5__prevent_overbooking.sql` to clean existing seed duplicates (marked `CANCELLED`) and created partial unique index `idx_bookings_active_member_slot ON bookings (slot_id, member_id) WHERE status = 'CONFIRMED'`.
  3. Updated `AdminController.attendance` with `DISTINCT ON (member_id)` ordered by `b.created_at, b.id`.
- **How it was verified**: Created `ConcurrentBookingTest.java`. Before fix, multi-threaded overbooking test failed (`expected: <1> but was: <4>`). After fix, both overbooking and duplicate booking tests passed with 0 failures.

---

## T1.4 Refund policy for cancellations > 12 hours before class
- **Status**: Done
- **What was found**: `BookingService.java:131` checked `Duration.between(now, slot.startsAt()).toHours() >= 24` instead of 12 hours, giving members cancelling 20 hours in advance only a 50% refund. Furthermore, `RefundCalculator.java` truncated floats with `(long)(refund * 100) / 100.0` instead of rounding half-up.
- **What was done**: Changed threshold to 12 hours via `RefundCalculator.isEarly(now, slot.startsAt(), zone)` using `centreClock.zone()`. Switched `RefundCalculator` to `BigDecimal` with `RoundingMode.HALF_UP` to 2 decimal places. Updated frontend text in `MyBookings.jsx` to "12 hours".
- **How it was verified**: Ran 4 unit tests in `RefundCalculatorTest.java` and integration tests in `BookingFlowTest.java`. All passed.

---

## T1.5 Error on My Plan for new members
- **Status**: Done
- **What was found**: When members sign up via `/api/auth/signup` or exist as seeded members 33–35 (`rohan@slotbook.test`, etc.), `plan_id` is `null`. In `MeController.java:40`, `members.findPlan(member.planId()).orElseThrow()` threw `NoSuchElementException: No value present` (HTTP 500), causing `MyPlan.jsx` to render an error page.
- **What was done**:
  1. Added null check in `MemberRepository.findPlan(Long id)` to return `Optional.empty()` without querying when `id == null`.
  2. Handled `plan == null` in `MeController.java`, returning `{"plan": null, "classesPerMonth": 0, "monthlyPrice": 0.0, "classesUsedThisMonth": used}`.
  3. Added an empty-state UI in `MyPlan.jsx` displaying "No active plan" and current monthly class count.
- **How it was verified**: Created `NewMemberPlanTest.java`. Before fix, calling `/api/me/plan` failed with HTTP 500. After fix, it passed with 200 OK and expected null plan response.

---

## T1.6 Parents booking classes for their children from their own account
- **Status**: Skipped (Proposed minimal design; awaiting approval before implementation)
- **What was found**: Feature request to allow parents to manage and book classes for children under their account.
- **What was done**: Evaluated constraints (caller authentication, child ownership check, parent payment, family plan quota pooling, privacy, and keeping `contract/` untouched). Formulated and proposed a minimal design using a `dependents` table and `dependent_id` on bookings. Implementation was intentionally skipped pending client/user approval.
- **How it was verified**: **Unverified (not implemented).**

---

## T1.7 Refund paisa mismatch — monthly total does not match gateway
- **Status**: Done
- **What was found**: October refund report returned ₹6853.30 while gateway settlement CSV showed ₹6853.50 (₹0.20 discrepancy). Root causes: 20 rows in `V3__october_history.sql` were truncated instead of rounded HALF_UP; `AdminController.java` applied `Math.floor` on the total; and month boundaries were evaluated in UTC instead of `Asia/Kolkata`. Non-admin members could also access the report.
- **What was done**:
  1. Created Flyway migration `V4__fix_october_refunds.sql` updating the 20 rows to their exact HALF_UP amounts (e.g. 121.90→121.91).
  2. Fixed `AdminController.java` to use `centreClock.zone()` for month boundaries and `BigDecimal` accumulation for the total.
  3. Enforced admin authorization with `current.requireAdmin()`.
- **How it was verified**: Created `AdminRefundReportTest.java` (3 tests) asserting the report matches `docs/gateway-refunds-2026-10.csv` row-for-row with total ₹6853.50, and verifying 401/403 access control. All passed.

---

## T2. Waitlist module
- **Status**: Done
- **What was built**: Built complete waitlist module in `modules/waitlist/` implementing all 5 endpoints (`POST /api/modules/waitlist/slots/{slotId}/join`, `DELETE /me`, `GET /slots/{slotId}`, admin view and admin clear). Implemented `WaitlistListener` with idempotent event processing for `BookingCancelled`, auto-booking the next waitlisted member.
- **How it was verified**: Verified with `WaitlistContractTest` (5 tests) and `WaitlistExtraTest` (7 tests). All passed.

---

## T3. Booking Assistant
- **Status**: Done
- **What was built**: Implemented AI booking assistant at `POST /api/assistant/chat` with Google Gemini API (`gemini-1.5-flash` / `gemini-2.0-flash` with automatic model fallback, free tier) via plain HTTP (`java.net.http.HttpClient`). Implemented 4 function calling tools (`list_classes`, `book`, `cancel`, `list_my_bookings`) with server-side identity enforcement (`CurrentMember.require()`), cancellation ownership validation, timezone anchoring in `Asia/Kolkata`, ambiguity detection, and conversation safety bounds. Created `ASSISTANT.md`.
- **How it was verified**: Verified with `AssistantTest.java` (5 tests covering 401 unauthenticated, cross-member cancellation rejection, ambiguity clarification, end-to-end booking tool call, and missing API key handling). All passed.

---

## T4. Settlement (Wave 2)
- **Status**: Done
- **What was found**: Instructors are paid from member class fees. Core publishes every charge and refund on `Topics.PAYMENTS` (`PaymentRecorded`). Settlement must listen to payments, store them idempotently, batch them periodically (`slotbook.settlement.interval-seconds`, default 300s), calculate net charges/refunds and 70% net payout per instructor, seal batches immutably, and handle repeated messages, delayed broker delivery, and simulated DB crashes.
- **What was built**:
  1. Created Flyway migration `V300_1__settlement.sql` with tables `settlement_batches`, `settlement_payments` (with primary key on `event_id` and unique assignment to `batch_id`), and `settlement_lines`.
  2. Implemented `SettlementListener` subscribing to `Topics.PAYMENTS` (`PaymentRecorded`) using `@JmsListener(destination = Topics.PAYMENTS, containerFactory = Topics.LISTENER_FACTORY, subscription = "settlement")`. Idempotent insert with `ON CONFLICT (event_id) DO NOTHING`.
  3. Implemented `SettlementService.settle()`: atomically claims unsettled payments into a new batch, aggregates charges and refunds per instructor, computes 70% net payout with `BigDecimal.setScale(2, RoundingMode.HALF_UP)`, inserts line items, and deletes empty batches.
  4. Scheduled batch closure in `SettlementScheduler` every `slotbook.settlement.interval-seconds`.
  5. Implemented admin endpoints in `SettlementController`: `POST /api/modules/settlement/admin/settle`, `GET /batches`, `GET /batches/{id}` with admin security enforcement (`current.requireAdmin()`).
- **How it was verified**:
  1. Verified with `SettlementContractTest.java` (3 contract tests covering admin settle, batch listing, and line-item details). All passed.
  2. Verified with the 5-minute chaos runner `SettlementChaosRun` (`chaos.cmd` / `./chaos.sh`): sent 600 payments with duplicate broker deliveries (20%), delivery delays up to 8000ms, and simulated database failures (3%). All 600 payments were settled exactly once across immutable closed batches, with 0 discrepancies and 0 problems found.
