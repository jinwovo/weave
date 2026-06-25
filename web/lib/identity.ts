// Deterministic, exchange-free identity: every client derives the same colour + display name for a
// given actor id straight from the id, so cursors and presence are labelled consistently without
// anyone having to broadcast "my name is…".

const COLORS = ['#ef4444', '#f59e0b', '#10b981', '#06b6d4', '#3b82f6', '#8b5cf6', '#ec4899', '#84cc16'];
const ADJ = ['swift', 'calm', 'brave', 'lucky', 'clever', 'bright', 'mellow', 'keen'];
const ANIMAL = ['otter', 'falcon', 'koala', 'lynx', 'heron', 'panda', 'tapir', 'orca'];

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export function colorFor(actor: string): string {
  return COLORS[hash(actor) % COLORS.length];
}

export function nameFor(actor: string): string {
  const h = hash(actor);
  return `${ADJ[h % ADJ.length]}-${ANIMAL[(h >> 3) % ANIMAL.length]}`;
}

export function initialsFor(actor: string): string {
  return nameFor(actor).split('-').map((p) => p[0]?.toUpperCase() ?? '').join('');
}
