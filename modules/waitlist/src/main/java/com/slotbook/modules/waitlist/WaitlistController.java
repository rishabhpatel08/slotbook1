package com.slotbook.modules.waitlist;

import com.slotbook.contract.Bookings;
import com.slotbook.contract.CurrentMember;
import com.slotbook.contract.MemberView;
import com.slotbook.contract.Members;
import com.slotbook.contract.SlotView;
import com.slotbook.contract.Slots;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/modules/waitlist")
class WaitlistController {

    private final CurrentMember current;
    private final WaitlistRepository repo;
    private final Slots slots;
    private final Bookings bookings;
    private final Members members;

    WaitlistController(CurrentMember current, WaitlistRepository repo, Slots slots, Bookings bookings, Members members) {
        this.current = current;
        this.repo = repo;
        this.slots = slots;
        this.bookings = bookings;
        this.members = members;
    }

    /** Join the waitlist for a full slot. 409 if the slot has free seats, member already on list, or already booked. */
    @PostMapping("/slots/{slotId}/join")
    public Map<String, Object> join(@PathVariable long slotId) {
        MemberView me = current.require();

        SlotView slot = slots.find(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found"));

        if (!slot.isFull()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot has free seats — book directly instead");
        }

        // Check if already has a confirmed booking for this slot
        List<?> confirmed = bookings.confirmedForMember(me.id()).stream()
                .filter(b -> b.slotId() == slotId && b.isConfirmed())
                .toList();
        if (!confirmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a booking for this slot");
        }

        Optional<Integer> position = repo.join(slotId, me.id());
        if (position.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already on the waitlist");
        }

        return Map.of("position", position.get());
    }

    /** Leave the waitlist. */
    @DeleteMapping("/slots/{slotId}/me")
    public ResponseEntity<Void> leave(@PathVariable long slotId) {
        MemberView me = current.require();
        repo.leave(slotId, me.id());
        return ResponseEntity.noContent().build();
    }

    /** View the waitlist for a slot. Members see their own name but other entries show "Member N". */
    @GetMapping("/slots/{slotId}")
    public Map<String, Object> view(@PathVariable long slotId) {
        MemberView me = current.require();
        List<WaitlistRepository.Entry> entries = repo.entriesForSlot(slotId);

        int position = 0;
        List<Map<String, Object>> entryList = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            WaitlistRepository.Entry e = entries.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("position", i + 1);
            if (e.memberId() == me.id()) {
                position = i + 1;
                item.put("name", me.name());
            } else {
                item.put("name", "Member " + (i + 1));
            }
            entryList.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size", entries.size());
        result.put("myPosition", position > 0 ? position : null);
        result.put("entries", entryList);
        return result;
    }

    /** Admin: view the waitlist with real names. */
    @GetMapping("/admin/slots/{slotId}")
    public Map<String, Object> adminView(@PathVariable long slotId) {
        current.requireAdmin();
        List<WaitlistRepository.Entry> entries = repo.entriesForSlot(slotId);

        List<Map<String, Object>> entryList = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            WaitlistRepository.Entry e = entries.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("position", i + 1);
            String name = members.find(e.memberId()).map(MemberView::name).orElse("Unknown");
            item.put("name", name);
            item.put("memberId", e.memberId());
            entryList.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size", entries.size());
        result.put("entries", entryList);
        return result;
    }

    /** Admin: clear the entire waitlist for a slot. */
    @DeleteMapping("/admin/slots/{slotId}")
    public ResponseEntity<Void> adminClear(@PathVariable long slotId) {
        current.requireAdmin();
        repo.clearSlot(slotId);
        return ResponseEntity.noContent().build();
    }
}
