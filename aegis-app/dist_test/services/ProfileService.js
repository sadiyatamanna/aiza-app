"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.profileService = exports.ProfileService = void 0;
const SupabaseClient_1 = require("./SupabaseClient");
class ProfileService {
    constructor() { }
    static getInstance() {
        if (!ProfileService.instance) {
            ProfileService.instance = new ProfileService();
        }
        return ProfileService.instance;
    }
    /**
     * Create or upsert a user profile associated with authenticated user ID
     */
    async createProfile(profile) {
        try {
            const record = {
                id: profile.id,
                name: profile.name.trim(),
                email: profile.email.trim(),
                phone: profile.phone.trim(),
                updated_at: new Date().toISOString(),
            };
            const { data, error } = await SupabaseClient_1.supabase
                .from('profiles')
                .upsert(record)
                .select()
                .single();
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: data };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to create profile' };
        }
    }
    /**
     * Retrieve profile by user ID
     */
    async getProfile(userId) {
        try {
            const { data, error } = await SupabaseClient_1.supabase
                .from('profiles')
                .select('id, name, email, phone, created_at, updated_at')
                .eq('id', userId)
                .maybeSingle();
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: data || null };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to get profile' };
        }
    }
    /**
     * Update profile fields for authenticated user
     */
    async updateProfile(userId, updates) {
        try {
            const payload = {
                ...updates,
                updated_at: new Date().toISOString(),
            };
            const { data, error } = await SupabaseClient_1.supabase
                .from('profiles')
                .update(payload)
                .eq('id', userId)
                .select()
                .single();
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true, data: data };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to update profile' };
        }
    }
}
exports.ProfileService = ProfileService;
ProfileService.instance = null;
exports.profileService = ProfileService.getInstance();
