// Supabase Edge Function: delete-account
// Secure, server-side permanent user account deletion.
//
// Requirements:
// - Validates the authenticated user's JWT from Authorization header.
// - Authorizes deletion ONLY for auth.uid() matching the token.
// - Performs paginated cleanup of user storage objects in 'aiza-evidence' bucket.
// - Deletes associated user records across database tables (evidence metadata, incidents, recipients, chat, pairing requests, emergency contacts, profile).
// - Permanently deletes the user using SUPABASE_SERVICE_ROLE_KEY on the server.
// - NEVER exposes service_role key to Android client.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req: Request) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return new Response(
        JSON.stringify({ error: 'Missing or invalid Authorization header' }),
        { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const token = authHeader.replace('Bearer ', '').trim()
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ''
    const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY') ?? ''
    const supabaseServiceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''

    if (!supabaseUrl || !supabaseServiceRoleKey) {
      return new Response(
        JSON.stringify({ error: 'Server configuration error: missing service environment variables' }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // 1. Verify authenticated user identity with user client
    const userClient = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: `Bearer ${token}` } },
      auth: { persistSession: false }
    })

    const { data: { user }, error: userError } = await userClient.auth.getUser(token)
    if (userError || !user || !user.id) {
      return new Response(
        JSON.stringify({ error: 'Invalid or expired user session: ' + (userError?.message ?? 'User not found') }),
        { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const userId = user.id

    // 2. Privileged admin client using server-side service-role key
    const adminClient = createClient(supabaseUrl, supabaseServiceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false }
    })

    // 3. Paginated cleanup of owned objects in 'aiza-evidence' bucket
    const userFolder = `user_${userId}`
    let hasMoreFiles = true
    let fileOffset = 0
    const pageSize = 100

    while (hasMoreFiles) {
      const { data: files, error: listError } = await adminClient.storage
        .from('aiza-evidence')
        .list(userFolder, { limit: pageSize, offset: fileOffset, sortBy: { column: 'name', order: 'asc' } })

      if (listError) {
        // If bucket does not exist or listing fails, log warning
        console.warn('Storage list warning:', listError.message)
        hasMoreFiles = false
      } else if (!files || files.length === 0) {
        hasMoreFiles = false
      } else {
        const filePaths = files
          .filter((f: any) => f.name && f.id)
          .map((f: any) => `${userFolder}/${f.name}`)

        if (filePaths.length > 0) {
          const { error: removeError } = await adminClient.storage
            .from('aiza-evidence')
            .remove(filePaths)

          if (removeError) {
            console.error('Error removing user evidence files:', removeError.message)
            return new Response(
              JSON.stringify({ error: `Failed to remove user evidence files from cloud storage: ${removeError.message}` }),
              { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            )
          }
        }

        if (files.length < pageSize) {
          hasMoreFiles = false
        } else {
          fileOffset += files.length
        }
      }
    }

    // Helper function to safely delete rows from a table
    const safeDeleteFromTable = async (tableName: string, queryBuilder: (query: any) => any) => {
      try {
        const query = adminClient.from(tableName).delete()
        const { error } = await queryBuilder(query)
        if (error) {
          // Ignore if table does not exist in schema (PGRST205 or 42P01)
          const isTableMissing = error.code === 'PGRST205' || error.code === '42P01' || error.message?.includes('does not exist')
          if (!isTableMissing) {
            console.warn(`Warning deleting from ${tableName}:`, error.message)
          }
        }
      } catch (err: any) {
        console.warn(`Exception deleting from ${tableName}:`, err?.message)
      }
    }

    // 4. Delete user metadata rows in public.aiza_evidence
    await safeDeleteFromTable('aiza_evidence', (q) => q.eq('user_id', userId))

    // 5. Delete emergency incident recipients and incidents
    await safeDeleteFromTable('emergency_incident_recipients', (q) => q.eq('recipient_user_id', userId))
    await safeDeleteFromTable('emergency_incidents', (q) => q.eq('sender_id', userId))

    // 6. Delete chat messages
    await safeDeleteFromTable('guardian_chat_messages', (q) => q.or(`sender_id.eq.${userId},recipient_id.eq.${userId}`))

    // 7. Delete pairing requests
    await safeDeleteFromTable('guardian_pairing_requests', (q) => q.or(`sender_user_id.eq.${userId},target_user_id.eq.${userId}`))

    // 8. Delete user locations and SOS alerts
    await safeDeleteFromTable('user_locations', (q) => q.eq('user_id', userId))
    await safeDeleteFromTable('sos_alerts', (q) => q.eq('user_id', userId))

    // 9. Delete emergency contacts
    await safeDeleteFromTable('emergency_contacts', (q) => q.or(`user_id.eq.${userId},guardian_user_id.eq.${userId}`))

    // 10. Delete user profile
    await safeDeleteFromTable('profiles', (q) => q.eq('id', userId))

    // 11. Permanently delete user from Supabase Auth admin
    const { error: deleteUserError } = await adminClient.auth.admin.deleteUser(userId)
    if (deleteUserError) {
      console.error('Failed to delete user account from auth:', deleteUserError.message)
      return new Response(
        JSON.stringify({ error: 'Failed to delete user account from authentication service: ' + deleteUserError.message }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    return new Response(
      JSON.stringify({
        success: true,
        message: 'Account, server-side vault evidence, and associated cloud records permanently deleted.'
      }),
      { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (err: any) {
    return new Response(
      JSON.stringify({ error: 'Unexpected error during account deletion: ' + (err?.message ?? 'Unknown') }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
