// Polyfills ANTES de qualquer import do app (STOMP precisa de TextEncoder e
// crypto.getRandomValues em RN bare). Ordem importa.
import 'react-native-get-random-values';
import { TextEncoder, TextDecoder } from 'text-encoding';
if (!global.TextEncoder) global.TextEncoder = TextEncoder;
if (!global.TextDecoder) global.TextDecoder = TextDecoder;

import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => App);
