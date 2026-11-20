import { useEffect, useState } from 'react'
import { api } from '../api.js'

function formatNotificationTime(isoString) {
  if (!isoString) return ''
  const d = new Date(isoString)
  return new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).format(d).replace('am', 'AM').replace('pm', 'PM')
}

export default function Notifications() {
  const [items, setItems] = useState([])

  useEffect(() => {
    api.get('/api/notifications').then(setItems)
  }, [])

  async function markRead() {
    await api.post('/api/notifications/read')
    setItems(items.map((n) => ({ ...n, read: true })))
  }

  return (
    <div>
      <button onClick={markRead}>Mark all read</button>
      {items.length === 0 && <p>No notifications.</p>}
      {items.map((n) => (
        <div key={n.id} className={`card ${n.read ? 'muted' : ''}`}>
          {n.message}
          <div className="muted small">{formatNotificationTime(n.createdAt)}</div>
        </div>
      ))}
    </div>
  )
}
