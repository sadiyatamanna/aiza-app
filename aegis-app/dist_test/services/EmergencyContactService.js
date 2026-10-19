"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.emergencyContactService = exports.EmergencyContactService = void 0;
const SupabaseClient_1 = require("./SupabaseClient");
class EmergencyContactService {
    constructor() { }
    static getInstance() {
        if (!EmergencyContactService.instance) {
            EmergencyContactService.instance = new EmergencyContactService();
        }
        return EmergencyContactService.instance;
    }
    /**
     * Fetch contacts for authenticated user ordered by priority
     */
    async getContacts(userId) {
        try {
            const { data, error } = await SupabaseClient_1.supabase
                .from('emergency_contacts')
                .select('*')
                .eq('user_id', userId)
                .order('priority', { ascending: true });
            if (error) {
                return { success: false, error: error.message };
            }
            // Map safely in case schema column is "phone" or "phone number"
            const contacts = (data || []).map((row) => ({
                id: row.id,
                user_id: row.user_id,
                name: row.name,
                phone: row.phone || row['phone number'] || '',
                relationship: row.relationship || 'Emergency Contact',
                priority: String(row.priority || '1'),
                created_at: row.created_at,
                updated_at: row.updated_at,
            }));
            return { success: true, data: contacts };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to fetch emergency contacts' };
        }
    }
    /**
     * Add a new emergency contact for authenticated user.
     * Preserves canonical UUID when contact.id is provided.
     */
    async addContact(contact) {
        try {
            const payload = {
                ...(contact.id ? { id: contact.id } : {}),
                user_id: contact.userId,
                name: contact.name.trim(),
                phone: contact.phone.trim(),
                relationship: contact.relationship?.trim() || 'Emergency Contact',
                priority: String(contact.priority ?? '1'),
                created_at: new Date().toISOString(),
                updated_at: new Date().toISOString(),
            };
            const { data, error } = await SupabaseClient_1.supabase
                .from('emergency_contacts')
                .insert(payload)
                .select()
                .single();
            if (error) {
                return { success: false, error: error.message };
            }
            const row = data;
            return {
                success: true,
                data: {
                    id: row.id,
                    user_id: row.user_id,
                    name: row.name,
                    phone: row.phone || row['phone number'] || '',
                    relationship: row.relationship,
                    priority: String(row.priority),
                    created_at: row.created_at,
                    updated_at: row.updated_at,
                },
            };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to add emergency contact' };
        }
    }
    /**
     * Update an existing emergency contact
     */
    async updateContact(contactId, userId, updates) {
        try {
            const payload = {
                updated_at: new Date().toISOString(),
            };
            if (updates.name !== undefined)
                payload.name = updates.name.trim();
            if (updates.phone !== undefined)
                payload.phone = updates.phone.trim();
            if (updates.relationship !== undefined)
                payload.relationship = updates.relationship.trim();
            if (updates.priority !== undefined)
                payload.priority = String(updates.priority);
            const { data, error } = await SupabaseClient_1.supabase
                .from('emergency_contacts')
                .update(payload)
                .eq('id', contactId)
                .eq('user_id', userId)
                .select()
                .single();
            if (error) {
                return { success: false, error: error.message };
            }
            const row = data;
            return {
                success: true,
                data: {
                    id: row.id,
                    user_id: row.user_id,
                    name: row.name,
                    phone: row.phone || row['phone number'] || '',
                    relationship: row.relationship,
                    priority: String(row.priority),
                    created_at: row.created_at,
                    updated_at: row.updated_at,
                },
            };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to update emergency contact' };
        }
    }
    /**
     * Delete contact by ID scoped to user_id
     */
    async deleteContact(contactId, userId) {
        try {
            const { error } = await SupabaseClient_1.supabase
                .from('emergency_contacts')
                .delete()
                .eq('id', contactId)
                .eq('user_id', userId);
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to delete emergency contact' };
        }
    }
    /**
     * Update priority order of an existing contact
     */
    async updateContactPriority(contactId, userId, priority) {
        try {
            const { error } = await SupabaseClient_1.supabase
                .from('emergency_contacts')
                .update({
                priority: String(priority),
                updated_at: new Date().toISOString(),
            })
                .eq('id', contactId)
                .eq('user_id', userId);
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true };
        }
        catch (err) {
            return { success: false, error: err?.message || 'Failed to update contact priority' };
        }
    }
}
exports.EmergencyContactService = EmergencyContactService;
EmergencyContactService.instance = null;
exports.emergencyContactService = EmergencyContactService.getInstance();
