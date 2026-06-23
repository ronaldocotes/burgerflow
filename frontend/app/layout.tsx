'use client';

import { Inter } from 'next/font/google';
import './globals.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from '@/components/ui/toaster';
import { ThemeProvider } from '@/components/theme-provider';
import { SocketProvider } from '@/components/socket-provider';
import { AuthProvider } from '@/components/auth-provider';
import { SidebarProvider } from '@/components/sidebar-provider';

const inter = Inter({ subsets: ['latin'] });

// Create a client
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60 * 1000, // 1 minute
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR" suppressHydrationWarning>
      <body className={inter.className}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider
            attribute="class"
            defaultTheme="system"
            enableSystem
            disableTransitionOnChange
          >
            <SocketProvider>
              <AuthProvider>
                <SidebarProvider>
                  {children}
                  <Toaster />
                </SidebarProvider>
              </AuthProvider>
            </SocketProvider>
          </ThemeProvider>
        </QueryClientProvider>
      </body>
    </html>
  );
}
