import { MD3LightTheme } from 'react-native-paper';
import { theme, palette } from './colors';

// Tema do React Native Paper alinhado a marca MenuFlow (verde 700).
export const paperTheme = {
  ...MD3LightTheme,
  colors: {
    ...MD3LightTheme.colors,
    primary: palette.primary[700],
    onPrimary: theme.text.onBrand,
    background: theme.bg.primary,
    surface: theme.bg.secondary,
    error: '#ef4444',
  },
};
