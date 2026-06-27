module.exports = {
  presets: ['module:@react-native/babel-preset', 'nativewind/babel'],
  plugins: [
    [
      'module-resolver',
      {
        root: ['./src'],
        alias: { '@': './src' },
        extensions: ['.ios.js', '.android.js', '.js', '.ts', '.tsx', '.jsx'],
      },
    ],
    // Reanimated 4: o plugin de worklets agora vem do pacote react-native-worklets.
    // DEVE ser sempre o ultimo plugin.
    'react-native-worklets/plugin',
  ],
};
