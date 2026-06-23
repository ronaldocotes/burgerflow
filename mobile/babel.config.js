module.exports = {
  presets: [
    'module:metro-react-native-babel-preset',
    '@babel/preset-typescript',
  ],
  plugins: [
    [
      'module-resolver',
      {
        root: ['./src'],
        extensions: ['.ios.js', '.android.js', '.js', '.ts', '.tsx', '.jsx'],
        alias: {
          '@': './src',
          '@/app': './src/app',
          '@/assets': './src/assets',
          '@/components': './src/components',
          '@/config': './src/config',
          '@/hooks': './src/hooks',
          '@/lib': './src/lib',
          '@/navigation': './src/navigation',
          '@/screens': './src/screens',
          '@/services': './src/services',
          '@/store': './src/store',
          '@/theme': './src/theme',
          '@/types': './src/types',
          '@/utils': './src/utils',
        },
      },
    ],
    'react-native-reanimated/plugin',
  ],
};
