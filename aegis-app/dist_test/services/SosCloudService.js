"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.sosCloudService = exports.SosCloudService = void 0;
const SupabaseClient_1 = require("./SupabaseClient");
class SosCloudService {
    constructor() { }
    static getInstance() {
        if (!SosCloudService.instance) {
            SosCloudService.instance = new SosCloudService();
        }
        return SosCloudService.instance;
    }
    /**
     * Insert a new SOS Alert into Supabase cloud table.
     * Maps both triggered_at and trigger_at to handle database schema variations safely.
     */
    async insertSosAlert(alert) {
        try {
            const now = alert.timestamp || Date.now();
            const payload = {
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
            const { data, error } = await SupabaseClient_1.supabase
                .from('sos_alerts')
                .insert(payload)
                .select()
                .single();
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: data };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to insert SOS alert' };
        }
    }
    /**
     * Retrieve SOS alerts for the authenticated user
     */
    async getSosAlerts(userId, limit = 50) {
        try {
            const { data, error } = await SupabaseClient_1.supabase
                .from('sos_alerts')
                .select('*')
                .eq('user_id', userId)
                .order('created_at', { ascending: false })
                .limit(limit);
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: (data || []) };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to fetch SOS alerts' };
        }
    }
}
exports.SosCloudService = SosCloudService;
SosCloudService.instance = null;
exports.sosCloudService = SosCloudService.getInstance();
