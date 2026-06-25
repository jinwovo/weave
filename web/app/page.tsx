import Board from '@/components/Board';

export default async function Page({ searchParams }: { searchParams: Promise<{ room?: string }> }) {
  const { room } = await searchParams;
  const trimmed = room?.trim();
  return <Board room={trimmed && trimmed.length > 0 ? trimmed : 'lobby'} />;
}
