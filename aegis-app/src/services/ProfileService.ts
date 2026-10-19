import { supabase } from './SupabaseClient';

/**
 * Supabase Profiles Table Record Interface
 * Matches: id (UUID PK -> auth.users.id), name, email, phone, created_at, updated_at
 */
export interface SupabaseProfileRecord {
  id: string;
  name: string;
  email: string;
  phone: string;
  created_at?: string;
  updated_at?: string;
}

export class ProfileService {
  private static instance: ProfileService | null = null;

  private constructor() {}

  public static getInstance(): ProfileService {
    if (!ProfileService.instance) {
      ProfileService.instance = new ProfileService();
    }
    return ProfileService.instance;
  }

  /**
   * Create or upsert a user profile associated with authenticated user ID
   */
  public async createProfile(profile: {
    id: string;
    name: string;
    email: string;
    phone: string;
  }): Promise<{ success: boolean; data?: SupabaseProfileRecord; error?: string }> {
    try {
      const record: Partial<SupabaseProfileRecord> = {
        id: profile.id,
        name: profile.name.trim(),
        email: profile.email.trim(),
        phone: profile.phone.trim(),
        updated_at: new Date().toISOString(),
      };

      const { data, error } = await supabase
        .from('profiles')
        .upsert(record)
        .select()
        .single();

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: data as SupabaseProfileRecord };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to create profile' };
    }
  }

  /**
   * Retrieve profile by user ID
   */
  public async getProfile(
    userId: string
  ): Promise<{ success: boolean; data?: SupabaseProfileRecord | null; error?: string }> {
    try {
      const { data, error } = await supabase
        .from('profiles')
        .select('id, name, email, phone, created_at, updated_at')
        .eq('id', userId)
        .maybeSingle();

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: (data as SupabaseProfileRecord) || null };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to get profile' };
    }
  }

  /**
   * Update profile fields for authenticated user
   */
  public async updateProfile(
    userId: string,
    updates: { name?: string; phone?: string; email?: string }
  ): Promise<{ success: boolean; data?: SupabaseProfileRecord; error?: string }> {
    try {
      const payload: Partial<SupabaseProfileRecord> = {
        ...updates,
        updated_at: new Date().toISOString(),
      };

      const { data, error } = await supabase
        .from('profiles')
        .update(payload)
        .eq('id', userId)
        .select()
        .single();

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: data as SupabaseProfileRecord };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to update profile' };
    }
  }
}

export const profileService = ProfileService.getInstance();
