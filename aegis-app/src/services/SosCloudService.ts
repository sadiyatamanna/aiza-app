import { supabase } from './SupabaseClient';

/**
 * Supabase SOS Alerts record structure.
 * Maps exactly to existing public.sos_alerts table columns:
 * - id: UUID (default gen_random_uuid())
 * - user_id: UUID
 * - latitude: DOUBLE PRECISION
 * - longitude: DOUBLE PRECISION
 * - location_available: BOOLEAN
 * - triggered_at: BIGINT (or trigger_at)
 * - status: TEXT ('ACTIVE', 'RESOLVED', 'CANCELLED', etc.)
 * - trigger_type: TEXT ('MANUAL', 'SHAKE', 'VOICE', 'BLE')
 * - encrypted_payload: TEXT
 * - signature: TEXT
 * - created_at: TIMESTAMPTZ
 */
export interface SupabaseSosAlertRecord {
  id?: string;
  user_id: string;
  latitude: number | null;
  longitude: number | null;
  location_available: boolean;
  triggered_at?: number;
  trigger_at?: number;
  status: string;
  trigger_type: string;
  encrypted_payload?: string;
  signature?: string;
  created_at?: string;
}

export class SosCloudService {
  private static instance: SosCloudService | null = null;

  private constructor() {}

  public static getInstance(): SosCloudService {
    if (!SosCloudService.instance) {
      SosCloudService.instance = new SosCloudService();
    }
    return SosCloudService.instance;
  }

  /**
   * Insert a new SOS Alert into Supabase cloud table.
   * Maps both triggered_at and trigger_at to handle database schema variations safely.
   */
  public async insertSosAlert(alert: {
    userId: string;
    latitude: number | null;
    longitude: number | null;
    locationAvailable?: boolean;
    triggerType?: string;
    status?: string;
    encryptedPayload?: string;
    signature?: string;
    timestamp?: number;
  }): Promise<{ success: boolean; data?: SupabaseSosAlertRecord; error?: string }> {
    try {
      const now = alert.timestamp || Date.now();
      const payload: any = {
        user_id: alert.userId,
        latitude: alert.latitude,
        longitude: alert.longitude,
        location_available: alert.locationAvailable !== undefined ? alert.locationAvailable : true,
        triggered_at: now,
        trigger_at: now, // Support both column names
        status: alert.status || 'ACTIVE',
        trigger_type: alert.triggerType || 'MANUAL',
        encrypted_payload: alert.encryptedPayload || '',
        signature: alert.signature || '',
        created_at: new Date(now).toISOString(),
      };

      const { data, error } = await supabase
        .from('sos_alerts')
        .insert(payload)
        .select()
        .single();

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: data as SupabaseSosAlertRecord };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to insert SOS alert' };
    }
  }

  /**
   * Retrieve SOS alerts for the authenticated user
   */
  public async getSosAlerts(
    userId: string,
    limit: number = 50
  ): Promise<{ success: boolean; data?: SupabaseSosAlertRecord[]; error?: string }> {
    try {
      const { data, error } = await supabase
        .from('sos_alerts')
        .select('*')
        .eq('user_id', userId)
        .order('created_at', { ascending: false })
        .limit(limit);

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true, data: (data || []) as SupabaseSosAlertRecord[] };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to fetch SOS alerts' };
    }
  }
}

export const sosCloudService = SosCloudService.getInstance();
