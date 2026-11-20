# SlotBook AI Booking Assistant

The booking assistant allows signed-in members to discover, book, and cancel classes through natural conversation (`POST /api/assistant/chat`).

---

## 1. Provider and Model

- **Provider**: Google Gemini API via plain HTTP (`java.net.http.HttpClient` with Jackson), requiring no third-party LLM SDK dependencies.
- **Default Model**: `gemini-1.5-flash` (free tier, with fallback to `gemini-2.0-flash`).
  - Can be overridden via the `LLM_MODEL` environment variable (e.g., `gemini-2.0-flash`, `gemini-1.5-flash-8b`).
- **Authentication**: Reads the API key strictly from the `LLM_API_KEY` environment variable at runtime. The key is never hardcoded or committed to source control.

---

## 2. How to Try It

### Step 1: Set your Gemini API Key
In PowerShell:
```powershell
$env:LLM_API_KEY="your-gemini-api-key"
```
In Linux / macOS:
```bash
export LLM_API_KEY="your-gemini-api-key"
```

### Step 2: Start the SlotBook App
```powershell
.\mvnw.cmd -pl app -am spring-boot:run
```

### Step 3: Sign In to Get an Auth Token
```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "meera@slotbook.test", "password": "demo"}'
```
Copy the `"token"` value from the JSON response.

### Step 4: Interact with the Assistant

#### A. Discovering Classes
```bash
curl -X POST http://localhost:8080/api/assistant/chat \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "What classes are scheduled for tomorrow?"}
    ]
  }'
```

#### B. Viewing Existing Bookings
```bash
curl -X POST http://localhost:8080/api/assistant/chat \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "What classes have I booked?"}
    ]
  }'
```

#### C. Ambiguity Handling (Assistant asks before booking)
```bash
curl -X POST http://localhost:8080/api/assistant/chat \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "Book me into yoga"}
    ]
  }'
```
*If there are multiple yoga classes (e.g. 7:00 AM and 5:00 PM), the assistant lists the options and asks for clarification without booking.*

#### D. Booking a Specific Class
```bash
curl -X POST http://localhost:8080/api/assistant/chat \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "Book me into Maths Olympiad on Friday at 4:00 PM"}
    ]
  }'
```

#### E. Cancelling a Booking
```bash
curl -X POST http://localhost:8080/api/assistant/chat \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "Cancel my booking for Maths Olympiad"}
    ]
  }'
```

---

## 3. What Was Done to Prevent Wrong Actions

Security and correctness are enforced **in server-side code**, not left merely to prompt engineering:

1. **Member Identity Enforced Server-Side**:
   - `CurrentMember.require()` is called immediately on every request. The authenticated member is derived exclusively from the session token.
   - Tool functions (`book`, `cancel`, `list_my_bookings`) receive the authenticated `MemberView` directly from the Spring controller.
   - The tool schemas exposed to the LLM contain **no `memberId` parameter**, preventing the LLM from impersonating or acting on behalf of another user.

2. **Server-Side Authorization on Cancellations**:
   - In `AssistantTools.cancel(me, bookingId)`, the server queries the booking from `BookingRepository` and checks `booking.memberId() == me.id()`.
   - If a member asks the assistant to cancel someone else's booking, the tool call returns:
     `{"success": false, "error": "Unauthorized: this booking does not belong to you"}`
     and leaves the target booking completely untouched.

3. **Centre Timezone & Date Resolution**:
   - Class timestamps and relative terms ("today", "tomorrow", "Friday") are anchored to `CentreClock.zone()` (`Asia/Kolkata`).
   - The assistant's system instruction provides today's exact date, current day of week, and tomorrow's date in `Asia/Kolkata` so relative dates match the centre's schedule and avoid UTC midnight shifts.

4. **Ambiguity Prevention**:
   - The system prompt strictly prohibits the assistant from calling `book` or `cancel` if a request matches zero or multiple classes/bookings.
   - The assistant must first list the matching candidates and ask the user to clarify. Only once the user confirms a single slot/booking does the assistant call the corresponding tool.

5. **Resource and Abuse Limits**:
   - **Conversation history cap**: Max 20 messages per request to avoid context exhaustion.
   - **Message length limit**: Max 2,000 characters per message to prevent memory/prompt-flooding attacks.
   - **Tool execution limit**: Capped at at most 5 tool-execution rounds per request to prevent infinite loops.
   - **Tool output as data**: Tool responses are structured JSON objects returned inside function responses, never directly injected into user prompts.

6. **Graceful Error Handling**:
   - If `LLM_API_KEY` is missing or unconfigured, the endpoint cleanly returns:
     `{"reply": "The booking assistant is not configured: LLM_API_KEY environment variable is missing."}`
   - If the provider encounters network errors or rate limits, the endpoint returns:
     `{"reply": "The booking assistant is temporarily unavailable. Please try again later."}`
