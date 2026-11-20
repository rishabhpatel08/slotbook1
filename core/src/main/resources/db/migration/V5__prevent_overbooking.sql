-- Clean up duplicate active bookings from seed data before adding unique constraint.
-- For any (slot_id, member_id) with multiple CONFIRMED bookings, mark duplicates as CANCELLED.
update bookings
set status = 'CANCELLED', cancelled_at = now()
where id in (
    select id from (
        select id, row_number() over (partition by slot_id, member_id order by created_at, id) as rn
        from bookings
        where status = 'CONFIRMED'
    ) dups
    where rn > 1
);

-- Unique partial index preventing duplicate active bookings for the same member in the same slot.
create unique index idx_bookings_active_member_slot on bookings (slot_id, member_id) where status = 'CONFIRMED';
