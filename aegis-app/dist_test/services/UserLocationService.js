"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.userLocationService = exports.UserLocationService = void 0;
const SupabaseClient_1 = require("./SupabaseClient");
class UserLocationService {
    constructor() { }
    static getInstance() {
        if (!UserLocationService.instance) {
            UserLocationService.instance = new UserLocationService();
        }
        return UserLocationService.instance;
    }
    /**
     * Insert or upsert the latest user location to Supabase
     */
    async insertLocation(loc) {
        try {
            const now = loc.timestamp || Date.now();
            const payload = {
                user_id: loc.userId,
                latitude: loc.latitude,
                longitude: loc.longitude,
                accuracy: loc.accuracy !== undefined ? loc.accuracy : 0,
                is_sharing_enabled: loc.isSharingEnabled !== undefined ? loc.isSharingEnabled : true,
                battery_level: loc.batteryLevel !== undefined ? loc.batteryLevel : 100,
                timestamp: now,
                updated_at: new Date(now).toISOString(),
            };
            const { data, error } = await SupabaseClient_1.supabase
                .from('user_locations')
                .upsert(payload)
                .select()
                .single();
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: data };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to insert user location' };
        }
    }
    /**
     * Retrieve the latest location for a user
     */
    async getLatestLocation(userId) {
        try {
            const { data, error } = await SupabaseClient_1.supabase
                .from('user_locations')
                .select('*')
                .eq('user_id', userId)
                .order('timestamp', { ascending: false })
                .limit(1)
                .maybeSingle();
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: data || null };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to get latest user location' };
        }
    }
    /**
     * Retrieve location history records for a user
     */
    async getUserLocations(userId, limit = 100) {
        try {
            const { data, error } = await SupabaseClient_1.supabase
                .from('user_locations')
                .select('*')
                .eq('user_id', userId)
                .order('timestamp', { ascending: false })
                .limit(limit);
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: (data || []) };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to fetch user locations' };
        }
    }
}
exports.UserLocationService = UserLocationService;
UserLocationService.instance = null;
exports.userLocationService = UserLocationService.getInstance();
