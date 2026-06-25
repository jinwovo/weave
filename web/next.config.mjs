/** @type {import('next').NextConfig} */
const nextConfig = {
  // The CRDT core is hand-written; skip lint gating the build (no eslint config shipped).
  eslint: { ignoreDuringBuilds: true },
};

export default nextConfig;
