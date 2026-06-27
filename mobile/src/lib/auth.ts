import EncryptedStorage from 'react-native-encrypted-storage';

// Sessao guardada em EncryptedStorage (Keystore Android / Keychain iOS).
// API async: NUNCA usar AsyncStorage nem localStorage para token.
const KEYS = { TOKEN: 'mf_token', REFRESH: 'mf_refresh', TENANT: 'mf_tenant' };

export async function saveSession(token: string, refresh: string, tenant: string) {
  await Promise.all([
    EncryptedStorage.setItem(KEYS.TOKEN, token),
    EncryptedStorage.setItem(KEYS.REFRESH, refresh),
    EncryptedStorage.setItem(KEYS.TENANT, tenant),
  ]);
}

export async function getToken(): Promise<string | null> {
  return EncryptedStorage.getItem(KEYS.TOKEN);
}

export async function getRefreshToken(): Promise<string | null> {
  return EncryptedStorage.getItem(KEYS.REFRESH);
}

export async function getTenant(): Promise<string | null> {
  return EncryptedStorage.getItem(KEYS.TENANT);
}

export async function clearSession() {
  await Promise.all(Object.values(KEYS).map((k) => EncryptedStorage.removeItem(k)));
}
