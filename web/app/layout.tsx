import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'weave — multiplayer canvas',
  description: 'Real-time collaborative canvas with a hand-rolled CRDT',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
