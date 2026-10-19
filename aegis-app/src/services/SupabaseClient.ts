import { createClient } from '@supabase/supabase-js';

// Fallback memory storage for node environments / tests where AsyncStorage native module is absent
class MemoryStorage {
  private data: Map<string, string> = new Map();

  async getItem(key: string): Promise<string | null> {
    return this.data.has(key) ? this.data.get(key)! : null;
  }

  async setItem(key: string, value: string): Promise<void> {
    this.data.set(key, value);
  }

  async removeItem(key: string): Promise<void> {
    this.data.delete(key);
  }
}

const resolveStorage = () => {
  try {
    // In pure Node.js environments (test runners), use MemoryStorage directly
    if (typeof (globalThis as any).window === 'undefined' && typeof process !== 'undefined' && process.versions?.node) {
      return new MemoryStorage();
    }
    // If in React Native environment, use AsyncStorage
    const AsyncStorage = require('@react-native-async-storage/async-storage').default;
    if (AsyncStorage && typeof AsyncStorage.getItem === 'function') {
      return AsyncStorage;
    }
  } catch (e) {
    // Fall back to memory storage
  }
  return new MemoryStorage();
};

const DEFAULT_SUPABASE_URL = 'https://czhvorddmzhvjxtjxhop.supabase.co';
const DEFAULT_SUPABASE_KEY = 'sb_publishable_3kX30FC_bPtbpvIlWKEb1A_WPpjNG8x';

export const getSupabaseConfig = () => {
  const rawUrl = (typeof process !== 'undefined' && process.env?.SUPABASE_URL) || DEFAULT_SUPABASE_URL;
  // If URL ends with /rest/v1 or /rest/v1/, strip it down to root supabase domain
  const cleanUrl = rawUrl.replace(/\/rest\/v1\/?$/, '');
  const publishableKey =
    (typeof process !== 'undefined' && process.env?.SUPABASE_PUBLISHABLE_KEY) || DEFAULT_SUPABASE_KEY;

  return {
    supabaseUrl: cleanUrl,
    supabasePublishableKey: publishableKey,
  };
};

const { supabaseUrl, supabasePublishableKey } = getSupabaseConfig();

/**
 * Supabase Client initialized safely for React Native environment with persistent storage.
 * Strictly uses only public publishable key; never secret or service-role key.
 */
export const supabase = createClient(supabaseUrl, supabasePublishableKey, {
  auth: {
    storage: resolveStorage(),
    autoRefreshToken: true,
    persistSession: true,
    detectSessionInUrl: false,
  },
});
