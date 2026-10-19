import { supabase } from './SupabaseClient';

/**
 * Emergency contact Supabase record.
 * Handles existing table columns:
 * id: UUID
 * user_id: UUID
 * name: TEXT
 * phone: TEXT (or "phone number" if present)
 * relationship: TEXT
 * priority: TEXT
 * created_at: TIMESTAMPTZ
 * updated_at: TIMESTAMPTZ
 */
export interface SupabaseEmergencyContactRecord {
  id: string;
  user_id: string;
  name: string;
  phone: string;
  relationship: string;
  priority: string;
  created_at?: string;
  updated_at?: string;
}

export class EmergencyContactService {
  private static instance: EmergencyContactService | null = null;

  private constructor() {}

  public static getInstance(): EmergencyContactService {
    if (!EmergencyContactService.instance) {
      EmergencyContactService.instance = new EmergencyContactService();
    }
    return EmergencyContactService.instance;
  }

  /**
   * Fetch contacts for authenticated user ordered by priority
   */
  public async getContacts(
    userId: string
  ): Promise<{ success: boolean; data?: SupabaseEmergencyContactRecord[]; error?: string }> {
    try {
      const { data, error } = await supabase
        .from('emergency_contacts')
        .select('*')
        .eq('user_id', userId)
        .order('priority', { ascending: true });

      if (error) {
        return { success: false, error: error.message };
      }

      // Map safely in case schema column is "phone" or "phone number"
      const contacts: SupabaseEmergencyContactRecord[] = (data || []).map((row: any) => ({
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
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to fetch emergency contacts' };
    }
  }

  /**
   * Add a new emergency contact for authenticated user.
   * Preserves canonical UUID when contact.id is provided.
   */
  public async addContact(contact: {
    id?: string;
    userId: string;
    name: string;
    phone: string;
    relationship?: string;
    priority?: string | number;
  }): Promise<{ success: boolean; data?: SupabaseEmergencyContactRecord; error?: string }> {
    try {
      const payload: any = {
        ...(contact.id ? { id: contact.id } : {}),
        user_id: contact.userId,
        name: contact.name.trim(),
        phone: contact.phone.trim(),
        relationship: contact.relationship?.trim() || 'Emergency Contact',
        priority: String(contact.priority ?? '1'),
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      };

      const { data, error } = await supabase
        .from('emergency_contacts')
        .insert(payload)
        .select()
        .single();

      if (error) {
        return { success: false, error: error.message };
      }

      const row: any = data;
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
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to add emergency contact' };
    }
  }

  /**
   * Update an existing emergency contact
   */
  public async updateContact(
    contactId: string,
    userId: string,
    updates: {
      name?: string;
      phone?: string;
      relationship?: string;
      priority?: string | number;
    }
  ): Promise<{ success: boolean; data?: SupabaseEmergencyContactRecord; error?: string }> {
    try {
      const payload: any = {
        updated_at: new Date().toISOString(),
      };
      if (updates.name !== undefined) payload.name = updates.name.trim();
      if (updates.phone !== undefined) payload.phone = updates.phone.trim();
      if (updates.relationship !== undefined) payload.relationship = updates.relationship.trim();
      if (updates.priority !== undefined) payload.priority = String(updates.priority);

      const { data, error } = await supabase
        .from('emergency_contacts')
        .update(payload)
        .eq('id', contactId)
        .eq('user_id', userId)
        .select()
        .single();

      if (error) {
        return { success: false, error: error.message };
      }

      const row: any = data;
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
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to update emergency contact' };
    }
  }

  /**
   * Delete contact by ID scoped to user_id
   */
  public async deleteContact(
    contactId: string,
    userId: string
  ): Promise<{ success: boolean; error?: string }> {
    try {
      const { error } = await supabase
        .from('emergency_contacts')
        .delete()
        .eq('id', contactId)
        .eq('user_id', userId);

      if (error) {
        return { success: false, error: error.message };
      }

      return { success: true };
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to delete emergency contact' };
    }
  }

  /**
   * Update priority order of an existing contact
   */
  public async updateContactPriority(
    contactId: string,
    userId: string,
    priority: number | string
  ): Promise<{ success: boolean; error?: string }> {
    try {
      const { error } = await supabase
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
    } catch (err: any) {
      return { success: false, error: err?.message || 'Failed to update contact priority' };
    }
  }
}

export const emergencyContactService = EmergencyContactService.getInstance();
