# Wave 2: T4. Settlement

Instructors are paid from what members pay for their classes. Build the settlement module in `modules/settlement/`. It is already wired into the build and the app, and it is empty. Its migrations own versions `300.x`. Read the new **Messages** section of `CONTRACT.md` first.

- Core publishes every charge and refund on `Topics.PAYMENTS` (`PaymentRecorded`).
- Every few minutes, settlement closes a **batch**: every payment received since the last batch. Each payment is settled exactly once, in the first batch after it arrives. Read the interval from `slotbook.settlement.interval-seconds` (default `300`).
- A batch has one line per instructor with payments in it: their charges, their refunds, and their payout. An instructor's payout is their **70% share** of the net (charges minus refunds).
- A closed batch never changes.
- It has to hold up when messages repeat, arrive out of order or late, and when the database fails part-way through.

Endpoints (admin only):

| Method and path | Response |
| --- | --- |
| `POST /api/modules/settlement/admin/settle` | Closes a batch now: `200 {"batchId": 7}`, or `{"batchId": null}` if there was nothing to settle |
| `GET /api/modules/settlement/admin/batches` | `200 [{"id": 7, "closedAt": "..."}]`, oldest first |
| `GET /api/modules/settlement/admin/batches/{id}` | `200 {"id": 7, "closedAt": "...", "paymentEventIds": ["..."], "lines": [{"instructor": "Lakshmi Iyer", "charges": "1354.50", "refunds": "121.91", "payout": "862.82"}]}` |

Money is a decimal string in rupees with two places.

**Check it with the chaos runner:** `./chaos.sh` (Windows: `chaos.cmd`), about 5 minutes. It sends 600 payments through a broker that repeats and delays messages, while database statements on your tables fail now and then, and it reports every problem it finds.

Add a `T4` entry to `STATUS.md`.
