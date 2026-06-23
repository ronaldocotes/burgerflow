/** @type {import('next').NextConfig} */
const nextConfig = {
  // Enable React Strict Mode
  reactStrictMode: true,
  
  // Enable TypeScript strict mode
  typescript: {
    ignoreBuildErrors: false,
    ignoreDevErrors: false,
  },
  
  // Environment variables
  env: {
    NEXT_PUBLIC_API_URL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api/v1',
    NEXT_PUBLIC_WS_URL: process.env.NEXT_PUBLIC_WS_URL || 'ws://localhost:8080',
    NEXT_PUBLIC_APP_NAME: 'BurgerFlow',
    NEXT_PUBLIC_APP_VERSION: '1.0.0',
    NEXT_PUBLIC_ENVIRONMENT: process.env.NEXT_PUBLIC_ENVIRONMENT || 'development',
  },
  
  // Image optimization
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: '**',
        port: '',
        pathname: '/**',
      },
    ],
    minimumCacheAge: 300,
    deviceSizes: [640, 750, 828, 1080, 1200, 1920, 2048, 3840],
    imageSizes: [16, 32, 48, 64, 96, 128, 256, 384],
  },
  
  // Packaging optimization
  output: 'standalone',
  
  // Compression
  compress: true,
  
  // Experimental features
  experimental: {
    // Enable server actions
    serverActions: {
      bodySizeLimit: '2mb',
    },
    // Enable parallel routes
    ppr: false,
    // Enable server components in client components
    serverComponentsExternalPackages: [],
  },
  
  // Redirects
  async redirects() {
    return [
      // Redirect to login if not authenticated
      {
        source: '/dashboard',
        destination: '/login',
        has: [{ type: 'cookie', key: 'token', value: null }],
        permanent: false,
      },
    ];
  },
  
  // Headers
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          {
            key: 'X-DNS-Prefetch-Control',
            value: 'on',
          },
          {
            key: 'X-Frame-Options',
            value: 'SAMEORIGIN',
          },
          {
            key: 'X-Content-Type-Options',
            value: 'nosniff',
          },
          {
            key: 'Referrer-Policy',
            value: 'strict-origin-when-cross-origin',
          },
        ],
      },
    ];
  },
  
  // Webpack configuration
  webpack: (config, { buildId, dev, isServer, defaultLoaders, webpack }) => {
    // Add aliases
    config.resolve.alias = {
      ...config.resolve.alias,
    };
    
    // Enable source maps in production
    if (!dev) {
      config.devtool = 'source-map';
    }
    
    return config;
  },
  
  // Sentry configuration (optional)
  sentry: {
    hideSourceMaps: true,
  },
  
  // Analyze bundle size (development only)
  ...(process.env.ANALYZE === 'true' ? { analyze: true } : {}),
};

module.exports = nextConfig;
