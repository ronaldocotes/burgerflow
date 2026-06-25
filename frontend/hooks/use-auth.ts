"use client";

import { useSyncExternalStore } from "react";

type AuthState = {
  isAuthenticated: boolean;
  isLoading: boolean;
};

export function useAuth(): AuthState {
  const isAuthenticated = useSyncExternalStore(
    subscribeToStorage,
    getAuthSnapshot,
    getServerSnapshot,
  );

  return {
    isAuthenticated,
    isLoading: false,
  };
}

function subscribeToStorage(onStoreChange: () => void) {
  window.addEventListener("storage", onStoreChange);
  return () => window.removeEventListener("storage", onStoreChange);
}

function getAuthSnapshot() {
  return Boolean(window.localStorage.getItem("menuflow_access_token"));
}

function getServerSnapshot() {
  return false;
}
