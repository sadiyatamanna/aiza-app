import { supabase } from './SupabaseClient';
import { User, Session, AuthChangeEvent } from '@supabase/supabase-js';

export interface AuthResult {
  success: boolean;
  error?: string;
  user?: User | null;
  session?: Session | null;
}

export class AuthService {
  private static instance: AuthService | null = null;

  private constructor() {}

  public static getInstance(): AuthService {
    if (!AuthService.instance) {
      AuthService.instance = new AuthService();
    }
    return AuthService.instance;
  }

  /**
   * Email and password registration with optional user metadata (name, phone)
   */
  public async signUp(
    email: string,
    password: string,
    metadata?: { name?: string; phone?: string }
  ): Promise<AuthResult> {
    try {
      const trimmedEmail = email.trim();
      const { data, error } = await supabase.auth.signUp({
        email: trimmedEmail,
        password,
        options: {
          data: {
            name: metadata?.name?.trim() || '',
            phone: metadata?.phone?.trim() || '',
          },
        },
      });

      if (error) {
        return { success: false, error: error.message };
      }

      return {
        success: true,
        user: data.user,
        session: data.session,
      };
    } catch (err: any) {
      return {
        success: false,
        error: err?.message || 'An unexpected error occurred during sign up',
      };
    }
  }

  /**
   * Email and password login
   */
  public async signIn(email: string, password: string): Promise<AuthResult> {
    try {
      const trimmedEmail = email.trim();
      const { data, error } = await supabase.auth.signInWithPassword({
        email: trimmedEmail,
        password,
      });

      if (error) {
        return { success: false, error: error.message };
      }

      return {
        success: true,
        user: data.user,
        session: data.session,
      };
    } catch (err: any) {
      return {
        success: false,
        error: err?.message || 'An unexpected error occurred during sign in',
      };
    }
  }

  /**
   * Verify email OTP code for signup verification
   */
  public async verifyEmailOtp(
    email: string,
    token: string,
    type: 'signup' | 'email' = 'signup'
  ): Promise<AuthResult> {
    try {
      const trimmedEmail = email.trim();
      const trimmedToken = token.trim();
      const { data, error } = await supabase.auth.verifyOtp({
        email: trimmedEmail,
        token: trimmedToken,
        type,
      });

      if (error) {
        return { success: false, error: error.message };
      }

      return {
        success: true,
        user: data.user,
        session: data.session,
      };
    } catch (err: any) {
      return {
        success: false,
        error: err?.message || 'Verification token invalid or expired',
      };
    }
  }

  /**
   * Resend verification email/OTP
   */
  public async resendVerification(
    email: string,
    type: 'signup' = 'signup'
  ): Promise<{ success: boolean; error?: string }> {
    try {
      const trimmedEmail = email.trim();
      const { error } = await supabase.auth.resend({
        type,
        email: trimmedEmail,
      });

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true };
    } catch (err: any) {
      return {
        success: false,
        error: err?.message || 'Failed to resend verification code',
      };
    }
  }

  /**
   * Sign out current user and clear session
   */
  public async signOut(): Promise<{ success: boolean; error?: string }> {
    try {
      const { error } = await supabase.auth.signOut();
      if (error) {
        return { success: false, error: error.message };
      }
      return { success: true };
    } catch (err: any) {
      return {
        success: false,
        error: err?.message || 'Failed to sign out',
      };
    }
  }

  /**
   * Restore and retrieve the current active Supabase session
   */
  public async getCurrentSession(): Promise<Session | null> {
    try {
      const { data, error } = await supabase.auth.getSession();
      if (error || !data.session) {
        return null;
      }
      return data.session;
    } catch (err) {
      console.warn('[AuthService] Could not retrieve session:', err);
      return null;
    }
  }

  /**
   * Retrieve the current authenticated Supabase user
   */
  public async getCurrentUser(): Promise<User | null> {
    try {
      const { data, error } = await supabase.auth.getUser();
      if (error || !data.user) {
        return null;
      }
      return data.user;
    } catch (err) {
      console.warn('[AuthService] Could not retrieve user:', err);
      return null;
    }
  }

  /**
   * Subscribe to Supabase authentication state changes
   */
  public onAuthStateChange(
    callback: (event: AuthChangeEvent, session: Session | null) => void
  ): { unsubscribe: () => void } {
    const { data: authListener } = supabase.auth.onAuthStateChange((event, session) => {
      callback(event, session);
    });

    return {
      unsubscribe: () => {
        authListener?.subscription.unsubscribe();
      },
    };
  }
}

export const authService = AuthService.getInstance();
