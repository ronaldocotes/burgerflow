/** @type {import('tailwindcss').Config} */
// Espelha frontend/tailwind.config.ts. Os tokens bg/text/border do front usam
// CSS vars (var(--...)) que NAO resolvem em React Native -> aqui viram hex
// concretos, alinhados a src/theme/colors.ts.
module.exports = {
  presets: [require('nativewind/preset')],
  content: ['./App.tsx', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Primary - Verde (marca MenuFlow)
        primary: {
          50: '#ecfdf5',
          100: '#d1fae5',
          200: '#a7f3d0',
          300: '#6ee7b7',
          400: '#34d399',
          500: '#10b981',
          600: '#059669',
          700: '#047857',
          800: '#065f46',
          900: '#064e3b',
          950: '#022c22',
        },
        // Secondary - Amber
        secondary: {
          50: '#fffbeb',
          100: '#fef3c7',
          200: '#fde68a',
          300: '#fcd34d',
          400: '#fbbf24',
          500: '#f59e0b',
          600: '#d97706',
          700: '#b45309',
          800: '#92400e',
          900: '#78350f',
          950: '#451a03',
        },
        // Neutral - escala slate (de src/theme/colors.ts)
        neutral: {
          50: '#f8fafc',
          100: '#f1f5f9',
          200: '#e2e8f0',
          300: '#cbd5e1',
          400: '#94a3b8',
          500: '#64748b',
          600: '#475569',
          700: '#334155',
          800: '#1e293b',
          900: '#0f172a',
        },
        success: { DEFAULT: '#10b981', light: '#d1fae5', dark: '#065f46' },
        warning: { DEFAULT: '#f59e0b', light: '#fef3c7', dark: '#92400e' },
        error: { DEFAULT: '#ef4444', light: '#fee2e2', dark: '#991b1b' },
        info: { DEFAULT: '#3b82f6', light: '#dbeafe', dark: '#1e40af' },
        // KDS (mesmos tokens do front)
        kds: {
          pending: '#fbbf24',
          inPrep: '#3b82f6',
          ready: '#10b981',
          completed: '#065f46',
          cancelled: '#ef4444',
        },
        // PDV
        pos: { active: '#10b981', inactive: '#9ca3af', sold: '#ef4444' },
        // Tokens de superficie (hex concreto; sem CSS vars no RN)
        bg: { primary: '#ffffff', secondary: '#f8fafc', tertiary: '#f1f5f9' },
        text: { primary: '#0f172a', secondary: '#475569', muted: '#94a3b8' },
        border: { light: '#e2e8f0', medium: '#cbd5e1' },
      },
    },
  },
  plugins: [],
};
