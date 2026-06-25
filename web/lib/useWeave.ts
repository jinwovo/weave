'use client';

import { useEffect, useRef, useState } from 'react';
import { Status, WeaveClient } from './client';

const WS_BASE = process.env.NEXT_PUBLIC_WEAVE_WS ?? 'ws://localhost:8103';

/**
 * Owns a {@link WeaveClient} for the room's lifetime. React state is updated only on
 * status/presence/snapshot changes — per-op and per-cursor traffic is intentionally NOT routed
 * through React (the canvas render loop reads the client directly each frame), so a busy board
 * doesn't thrash the component tree.
 */
export function useWeave(room: string, actor: string) {
  const clientRef = useRef<WeaveClient | null>(null);
  const [status, setStatus] = useState<Status>('connecting');
  const [presence, setPresence] = useState<string[]>([]);

  useEffect(() => {
    const client = new WeaveClient(room, actor, WS_BASE, () => {
      setStatus(client.status);
      setPresence([...client.presence]);
    });
    clientRef.current = client;
    client.connect();
    return () => client.close();
  }, [room, actor]);

  return { clientRef, status, presence };
}
