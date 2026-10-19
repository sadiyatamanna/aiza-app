import { supabase } from './SupabaseClient';

/**
 * Supabase user_locations table record.
 * Columns:
 * - id: UUID (PK, default gen_random_uuid())
 * - user_id: UUID
 * - latitude: DOUBLE PRECISION
 * - longitude: DOUBLE PRECISION
 * - accuracy: REAL
 * - is_sharing_enabled: BOOLEAN (default true)
 * - battery_level: INTEGER (default 0)
 * - timestamp: BIGINT
 * - updated_at: TIMESTAMPTZ
 */
export interface SupabaseUserLocationRecord {
  id?: string;
  user_id: string;
  latitude: number;
  longitude: number;
  accuracy: number;
  is_sharing_enabled: boolean;
  battery_level: number;
  timestamp: number;
  updated_at?: string;
}

export class UserLocationService {
  private static instance: UserLocationService | null = null;

  private constructor() {}

  public static getInstance(): UserLocationService {
    if (!UserLocationService.instance) {
      UserLocationService.instance = new UserLocationService();
    }
    return UserLocationService.instance;
  }

  /**
   * Insert or upsert the latest user location to Supabase
   */
  public async insertLocation(loc: {
    userId: string;
    latitude: number;
    longitude: number;
    accuracy?: number;
    isSharingEnabled?: boolean;
    batteryLevel?: number;
    timestamp?: number;
  }): Promise<{ success: boolean; data?: SupabaseUserLocationRecord; error?: string }> {
    try {
      const now = loc.timestamp || Date.now();
      const payload: Partial<SupabaseUserLocationRecord> = {
        user_id: loc.userId,
        latitude: loc.latitude,
        longitude: loc.longitude,
        accuracy: loc.accuracy !== undefined ? loc.accuracy : 0,
        is_sharing_enabled: loc.isSharingEnabled !== undefined ? loc.isSharingEnabled : true,
        battery_level: loc.batteryLevel !== undefined ? loc.batteryLevel : 100,
        timestamp: now,
        updated_at: new Date(now).toISOString(),
      };

      const { data, error } = await supabase
        .from('user_locations')
        .upsert(payload)
        .select()
        .single();

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: data as SupabaseUserLocationRecord };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to insert user location' };
    }
  }

  /**
   * Retrieve the latest location for a user
   */
  public async getLatestLocation(
    userId: string
  ): Promise<{ success: boolean; data?: SupabaseUserLocationRecord | null; error?: string }> {
    try {
      const { data, error } = await supabase
        .from('user_locations')
        .select('*')
        .eq('user_id', userId)
        .order('timestamp', { ascending: false })
        .limit(1)
        .maybeSingle();

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: (data as SupabaseUserLocationRecord) || null };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to get latest user location' };
    }
  }

  /**
   * Retrieve location history records for a user
   */
  public async getUserLocations(
    userId: string,
    limit: number = 100
  ): Promise<{ success: boolean; data?: SupabaseUserLocationRecord[]; error?: string }> {
    try {
      const { data, error } = await supabase
        .from('user_locations')
        .select('*')
        .eq('user_id', userId)
        .order('timestamp', { ascending: false })
        .limit(limit);

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: (data || []) as SupabaseUserLocationRecord[] };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to fetch user locations' };
    }
  }
}

export const userLocationService = UserLocationService.getInstance();
