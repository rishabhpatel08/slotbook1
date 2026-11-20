import { useEffect, useState } from 'react'
import { api } from '../../api.js'

export default function WaitlistPanel({ slot, me, refresh }) {
  const [info, setInfo] = useState(null)
  const [error, setError] = useState(null)
  const isFull = slot.booked >= slot.capacity

  useEffect(() => {
    if (isFull) {
      api.get(`/api/modules/waitlist/slots/${slot.id}`)
        .then(setInfo)
        .catch(() => {})
    }
  }, [slot.id, slot.booked, isFull])

  async function join() {
    setError(null)
    try {
      await api.post(`/api/modules/waitlist/slots/${slot.id}/join`)
      const updated = await api.get(`/api/modules/waitlist/slots/${slot.id}`)
      setInfo(updated)
      if (refresh) refresh()
    } catch (e) {
      setError(e.message)
    }
  }

  async function leave() {
    setError(null)
    try {
      await api.del(`/api/modules/waitlist/slots/${slot.id}/me`)
      const updated = await api.get(`/api/modules/waitlist/slots/${slot.id}`)
      setInfo(updated)
      if (refresh) refresh()
    } catch (e) {
      setError(e.message)
    }
  }

  if (!isFull) return null

  return (
    <div className="panel" style={{ marginTop: '0.5rem', fontSize: '0.9rem' }}>
      {info && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '0.5rem' }}>
          <span>
            {info.myPosition ? (
              <strong>You are #{info.myPosition} on the waitlist ({info.size} waiting)</strong>
            ) : (
              <span>Waitlist: {info.size} {info.size === 1 ? 'person' : 'people'} waiting</span>
            )}
          </span>
          {info.myPosition ? (
            <button className="link" onClick={leave}>Leave waitlist</button>
          ) : (
            <button onClick={join}>Join waitlist</button>
          )}
        </div>
      )}
      {error && <p className="error" style={{ margin: '0.25rem 0 0 0' }}>{error}</p>}
    </div>
  )
}
