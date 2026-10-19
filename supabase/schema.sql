-- ==============================================================================
-- AEGIS DEFENSE & SAFETY - SUPABASE BACKEND SCHEMA
-- ==============================================================================
-- Complete PostgreSQL schema including tables, triggers, indexes,
-- Row Level Security (RLS) policies, and Realtime publications.

-- Enable UUID extension if not already enabled
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==============================================================================
-- 1. PROFILES TABLE
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL DEFAULT '',
    email TEXT DEFAULT '',
    phone TEXT DEFAULT '',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for phone/email queries
CREATE INDEX IF NOT EXISTS idx_profiles_phone ON public.profiles(phone);
CREATE INDEX IF NOT EXISTS idx_profiles_email ON public.profiles(email);

-- Enable RLS
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

-- Profiles Policies
DROP POLICY IF EXISTS "Users can view own profile" ON public.profiles;
CREATE POLICY "Users can view own profile"
    ON public.profiles FOR SELECT
    USING (auth.uid() = id);

-- Phase 4D: Allow saved emergency contacts / guardians to view the profile of the user who added them
DROP POLICY IF EXISTS "Saved contacts can view profile name of users who added them" ON public.profiles;
CREATE POLICY "Saved contacts can view profile name of users who added them"
    ON public.profiles FOR SELECT
    USING (
        auth.uid() = id
        OR EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.user_id = public.profiles.id
              AND ec.guardian_user_id = auth.uid()
        )
    );

CREATE POLICY "Users can insert own profile"
    ON public.profiles FOR INSERT
    WITH CHECK (auth.uid() = id);

CREATE POLICY "Users can update own profile"
    ON public.profiles FOR UPDATE
    USING (auth.uid() = id);

-- ==============================================================================
-- 2. EMERGENCY CONTACTS TABLE
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.emergency_contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    guardian_user_id UUID NULL,
    name TEXT NOT NULL,
    phone TEXT NOT NULL,
    relationship TEXT NOT NULL DEFAULT 'Emergency Contact',
    priority TEXT NOT NULL DEFAULT '1',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.emergency_contacts
    ADD COLUMN IF NOT EXISTS guardian_user_id UUID NULL;

-- Indexes for fast contact queries
CREATE INDEX IF NOT EXISTS idx_emergency_contacts_user_id ON public.emergency_contacts(user_id);
CREATE INDEX IF NOT EXISTS idx_emergency_contacts_guardian_user_id ON public.emergency_contacts(guardian_user_id);
CREATE INDEX IF NOT EXISTS idx_emergency_contacts_phone ON public.emergency_contacts(phone);

-- Enable RLS
ALTER TABLE public.emergency_contacts ENABLE ROW LEVEL SECURITY;

-- Emergency Contacts Policies
CREATE POLICY "Users can view own emergency contacts"
    ON public.emergency_contacts FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own emergency contacts"
    ON public.emergency_contacts FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own emergency contacts"
    ON public.emergency_contacts FOR UPDATE
    USING (auth.uid() = user_id);

CREATE POLICY "Users can delete own emergency contacts"
    ON public.emergency_contacts FOR DELETE
    USING (auth.uid() = user_id);

-- ==============================================================================
-- 3. SOS ALERTS TABLE (PQC Encrypted & Lattice Signed)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.sos_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    location_available BOOLEAN NOT NULL DEFAULT true,
    triggered_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    trigger_type TEXT NOT NULL DEFAULT 'MANUAL',
    encrypted_payload TEXT NOT NULL DEFAULT '',
    signature TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for real-time alerting & user lookups
CREATE INDEX IF NOT EXISTS idx_sos_alerts_user_id ON public.sos_alerts(user_id);
CREATE INDEX IF NOT EXISTS idx_sos_alerts_status ON public.sos_alerts(status);
CREATE INDEX IF NOT EXISTS idx_sos_alerts_triggered_at ON public.sos_alerts(triggered_at DESC);

-- Enable RLS
ALTER TABLE public.sos_alerts ENABLE ROW LEVEL SECURITY;

-- SOS Alerts Policies
CREATE POLICY "Users can insert own SOS alerts"
    ON public.sos_alerts FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can view own SOS alerts"
    ON public.sos_alerts FOR SELECT
    USING (auth.uid() = user_id);

-- Authorized recipients (contacts registered by sender) can view SOS alerts
CREATE POLICY "Authorized emergency contacts can view SOS alerts"
    ON public.sos_alerts FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            JOIN public.profiles p ON p.phone = ec.phone
            WHERE ec.user_id = public.sos_alerts.user_id
              AND p.id = auth.uid()
        )
    );

CREATE POLICY "Users can update own SOS alerts"
    ON public.sos_alerts FOR UPDATE
    USING (auth.uid() = user_id);

-- ==============================================================================
-- 4. AUTOMATIC PROFILE CREATION TRIGGER
-- ==============================================================================
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, email, name, phone)
    VALUES (
        NEW.id,
        COALESCE(NEW.email, ''),
        COALESCE(NEW.raw_user_meta_data->>'name', ''),
        COALESCE(NEW.raw_user_meta_data->>'phone', '')
    )
    ON CONFLICT (id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Trigger execution on auth.users insert
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- ==============================================================================
-- 5. REALTIME PUBLICATION SETUP
-- ==============================================================================
-- Add sos_alerts and user_locations to Supabase Realtime publication
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.sos_alerts;
        ALTER PUBLICATION supabase_realtime ADD TABLE public.user_locations;
    END IF;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- ==============================================================================
-- 6. CONTINUOUS USER LOCATIONS TABLE (Location Sharing Independent of SOS)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.user_locations (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy REAL DEFAULT 0.0,
    is_sharing_enabled BOOLEAN NOT NULL DEFAULT true,
    battery_level INTEGER,
    timestamp BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_user_locations_user_id ON public.user_locations(user_id);
CREATE INDEX IF NOT EXISTS idx_user_locations_timestamp ON public.user_locations(timestamp DESC);

-- Enable Row Level Security
ALTER TABLE public.user_locations ENABLE ROW LEVEL SECURITY;

-- Policy 1: Users can view their own location record
CREATE POLICY "Users can view own location"
    ON public.user_locations FOR SELECT
    USING (auth.uid() = user_id);

-- Policy 2: Users can insert or update their own location record
CREATE POLICY "Users can insert or update own location"
    ON public.user_locations FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Policy 3: Authorized emergency contacts can view shared user location
-- (Only if the user has enabled location sharing and the contact phone matches the viewer profile)
CREATE POLICY "Authorized emergency contacts can view shared user location"
    ON public.user_locations FOR SELECT
    USING (
        is_sharing_enabled = true AND (
            EXISTS (
                SELECT 1 FROM public.emergency_contacts ec
                JOIN public.profiles p ON p.phone = ec.phone
                WHERE ec.user_id = public.user_locations.user_id
                  AND p.id = auth.uid()
            )
        )
    );

-- ==============================================================================
-- 7. GUARDIAN ACCOUNT PAIRING REQUESTS TABLE & APPROVAL RPC
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.guardian_pairing_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    guardian_email TEXT NOT NULL,
    emergency_contact_id UUID NOT NULL REFERENCES public.emergency_contacts(id) ON DELETE CASCADE,
    guardian_user_id UUID NULL REFERENCES auth.users(id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    requester_name TEXT NOT NULL DEFAULT '',
    contact_name TEXT NOT NULL DEFAULT '',
    relationship TEXT NOT NULL DEFAULT 'Emergency Contact',
    guardian_phone TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

ALTER TABLE public.guardian_pairing_requests
    ADD COLUMN IF NOT EXISTS requester_name TEXT NOT NULL DEFAULT '';
ALTER TABLE public.guardian_pairing_requests
    ADD COLUMN IF NOT EXISTS contact_name TEXT NOT NULL DEFAULT '';
ALTER TABLE public.guardian_pairing_requests
    ADD COLUMN IF NOT EXISTS relationship TEXT NOT NULL DEFAULT 'Emergency Contact';
ALTER TABLE public.guardian_pairing_requests
    ADD COLUMN IF NOT EXISTS guardian_phone TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_guardian_pairing_requester
    ON public.guardian_pairing_requests(requester_user_id, emergency_contact_id);
CREATE INDEX IF NOT EXISTS idx_guardian_pairing_email_status
    ON public.guardian_pairing_requests(LOWER(guardian_email), status);

ALTER TABLE public.guardian_pairing_requests ENABLE ROW LEVEL SECURITY;

-- Requesters can view their own sent pairing requests
DROP POLICY IF EXISTS "Requesters can view own guardian pairing requests" ON public.guardian_pairing_requests;
CREATE POLICY "Requesters can view own guardian pairing requests"
    ON public.guardian_pairing_requests FOR SELECT
    USING (auth.uid() = requester_user_id);

-- Guardians can view pairing requests addressed to their authenticated email
DROP POLICY IF EXISTS "Guardians can view pairing requests for their email" ON public.guardian_pairing_requests;
CREATE POLICY "Guardians can view pairing requests for their email"
    ON public.guardian_pairing_requests FOR SELECT
    USING (
        LOWER(COALESCE(auth.jwt()->>'email', '')) =
        LOWER(guardian_pairing_requests.guardian_email)
    );

-- Allow guardians to read the emergency_contacts metadata row referenced by their pairing request
DROP POLICY IF EXISTS "Guardians can view emergency contact for their pairing request" ON public.emergency_contacts;
CREATE POLICY "Guardians can view emergency contact for their pairing request"
    ON public.emergency_contacts FOR SELECT
    USING (
        guardian_user_id = auth.uid()
        OR EXISTS (
            SELECT 1 FROM public.guardian_pairing_requests gpr
            WHERE gpr.emergency_contact_id = public.emergency_contacts.id
              AND LOWER(COALESCE(auth.jwt()->>'email', '')) = LOWER(gpr.guardian_email)
        )
    );

-- Requesters can insert PENDING pairing requests for their own emergency contacts (guardian_user_id must be NULL)
DROP POLICY IF EXISTS "Requesters can insert pending guardian pairing requests" ON public.guardian_pairing_requests;
CREATE POLICY "Requesters can insert pending guardian pairing requests"
    ON public.guardian_pairing_requests FOR INSERT
    WITH CHECK (
        auth.uid() = requester_user_id
        AND status = 'PENDING'
        AND guardian_user_id IS NULL
        AND EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.id = guardian_pairing_requests.emergency_contact_id
              AND ec.user_id = auth.uid()
        )
    );

-- Authenticated guardians can accept or reject requests addressed to their email
DROP POLICY IF EXISTS "Guardians can respond to pairing requests for their email" ON public.guardian_pairing_requests;
CREATE POLICY "Guardians can respond to pairing requests for their email"
    ON public.guardian_pairing_requests FOR UPDATE
    USING (
        LOWER(COALESCE(auth.jwt()->>'email', '')) =
        LOWER(guardian_pairing_requests.guardian_email)
    )
    WITH CHECK (
        LOWER(COALESCE(auth.jwt()->>'email', '')) =
        LOWER(guardian_pairing_requests.guardian_email)
        AND (
            (status = 'ACCEPTED' AND guardian_user_id = auth.uid())
            OR (status = 'REJECTED' AND guardian_user_id IS NULL)
        )
    );

-- Trigger that automatically updates emergency_contacts.guardian_user_id when an authenticated
-- guardian explicitly accepts a pairing request (status = 'ACCEPTED' and guardian_user_id = auth.uid())
CREATE OR REPLACE FUNCTION public.handle_guardian_pairing_accepted()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'ACCEPTED' AND NEW.guardian_user_id IS NOT NULL THEN
        UPDATE public.emergency_contacts
        SET guardian_user_id = NEW.guardian_user_id,
            updated_at = NOW()
        WHERE id = NEW.emergency_contact_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_guardian_pairing_accepted ON public.guardian_pairing_requests;
CREATE TRIGGER on_guardian_pairing_accepted
    AFTER UPDATE ON public.guardian_pairing_requests
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status = 'ACCEPTED')
    EXECUTE FUNCTION public.handle_guardian_pairing_accepted();

-- Secure RPC for explicit guardian approval: verifies the caller is authenticated as the
-- target guardian email, marks the request ACCEPTED with auth.uid(), and links emergency_contacts.guardian_user_id
CREATE OR REPLACE FUNCTION public.accept_guardian_pairing_request(p_request_id UUID)
RETURNS UUID AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_caller_email TEXT := LOWER(COALESCE(auth.jwt()->>'email', ''));
    v_contact_id UUID;
BEGIN
    IF v_caller_uid IS NULL OR v_caller_email = '' THEN
        RAISE EXCEPTION 'Authentication required to accept a guardian pairing request.';
    END IF;

    UPDATE public.guardian_pairing_requests
    SET status = 'ACCEPTED',
        guardian_user_id = v_caller_uid
    WHERE id = p_request_id
      AND LOWER(guardian_pairing_requests.guardian_email) = v_caller_email
      AND status IN ('PENDING', 'ACCEPTED')
    RETURNING emergency_contact_id INTO v_contact_id;

    IF v_contact_id IS NULL THEN
        RAISE EXCEPTION 'Pending guardian pairing request not found for current authenticated email.';
    END IF;

    UPDATE public.emergency_contacts
    SET guardian_user_id = v_caller_uid,
        updated_at = NOW()
    WHERE id = v_contact_id;

    RETURN v_caller_uid;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ==============================================================================
-- 8. EMERGENCY INCIDENTS TABLE (Phase 1 Foundation)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.emergency_incidents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    trigger_source TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at BIGINT NOT NULL,
    ended_at BIGINT NULL,
    latitude DOUBLE PRECISION NULL,
    longitude DOUBLE PRECISION NULL,
    location_timestamp BIGINT NULL,
    location_status TEXT NULL,
    battery_percent INTEGER NULL,
    network_status TEXT NULL,
    medical_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    medical_summary TEXT NULL,
    audio_recording_status TEXT NOT NULL DEFAULT 'NOT_STARTED',
    audio_duration_ms BIGINT NULL,
    audio_storage_path TEXT NULL,
    delivery_status TEXT NOT NULL DEFAULT 'PENDING',
    last_delivery_error TEXT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_emergency_incidents_user_id
    ON public.emergency_incidents(user_id);
CREATE INDEX IF NOT EXISTS idx_emergency_incidents_started_at
    ON public.emergency_incidents(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_emergency_incidents_delivery_status
    ON public.emergency_incidents(delivery_status);

ALTER TABLE public.emergency_incidents ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can select own emergency incidents" ON public.emergency_incidents;
CREATE POLICY "Users can select own emergency incidents"
    ON public.emergency_incidents FOR SELECT
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert own emergency incidents" ON public.emergency_incidents;
CREATE POLICY "Users can insert own emergency incidents"
    ON public.emergency_incidents FOR INSERT
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own emergency incidents" ON public.emergency_incidents;
CREATE POLICY "Users can update own emergency incidents"
    ON public.emergency_incidents FOR UPDATE
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete own emergency incidents" ON public.emergency_incidents;
CREATE POLICY "Users can delete own emergency incidents"
    ON public.emergency_incidents FOR DELETE
    USING (auth.uid() = user_id);

-- Phase 3: Read-only SELECT access for paired guardians verified via emergency_contacts.guardian_user_id
DROP POLICY IF EXISTS "Paired guardians can select sender emergency incidents" ON public.emergency_incidents;
CREATE POLICY "Paired guardians can select sender emergency incidents"
    ON public.emergency_incidents FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.guardian_user_id = auth.uid()
              AND ec.user_id = public.emergency_incidents.user_id
        )
    );

-- ==============================================================================
-- 9. EMERGENCY INCIDENT RECIPIENTS TABLE (Phase 3 Guardian Delivery & Ack)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.emergency_incident_recipients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES public.emergency_incidents(id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    guardian_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    delivery_status TEXT NOT NULL DEFAULT 'PENDING',
    delivered_at BIGINT NULL,
    last_error TEXT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_emergency_incident_recipient UNIQUE (incident_id, guardian_user_id)
);

CREATE INDEX IF NOT EXISTS idx_emergency_incident_recipients_incident_id
    ON public.emergency_incident_recipients(incident_id);
CREATE INDEX IF NOT EXISTS idx_emergency_incident_recipients_guardian_user_id
    ON public.emergency_incident_recipients(guardian_user_id);
CREATE INDEX IF NOT EXISTS idx_emergency_incident_recipients_delivery_status
    ON public.emergency_incident_recipients(delivery_status);

ALTER TABLE public.emergency_incident_recipients ENABLE ROW LEVEL SECURITY;

-- Senders can SELECT recipient rows for their own incidents
DROP POLICY IF EXISTS "Senders can select own emergency incident recipients" ON public.emergency_incident_recipients;
CREATE POLICY "Senders can select own emergency incident recipients"
    ON public.emergency_incident_recipients FOR SELECT
    USING (auth.uid() = sender_user_id);

-- Senders can INSERT recipient rows only for their own incidents and their paired guardians
DROP POLICY IF EXISTS "Senders can insert own emergency incident recipients" ON public.emergency_incident_recipients;
CREATE POLICY "Senders can insert own emergency incident recipients"
    ON public.emergency_incident_recipients FOR INSERT
    WITH CHECK (
        auth.uid() = sender_user_id
        AND guardian_user_id <> auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.emergency_incidents ei
            WHERE ei.id = public.emergency_incident_recipients.incident_id
              AND ei.user_id = auth.uid()
        )
        AND EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.user_id = auth.uid()
              AND ec.guardian_user_id = public.emergency_incident_recipients.guardian_user_id
        )
    );

-- Senders can UPDATE recipient rows belonging to their own incidents
DROP POLICY IF EXISTS "Senders can update own emergency incident recipients" ON public.emergency_incident_recipients;
CREATE POLICY "Senders can update own emergency incident recipients"
    ON public.emergency_incident_recipients FOR UPDATE
    USING (auth.uid() = sender_user_id)
    WITH CHECK (
        auth.uid() = sender_user_id
        AND guardian_user_id <> auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.user_id = auth.uid()
              AND ec.guardian_user_id = public.emergency_incident_recipients.guardian_user_id
        )
    );

-- Paired guardians can SELECT recipient rows addressed to their authenticated UUID
DROP POLICY IF EXISTS "Guardians can select own emergency incident recipient rows" ON public.emergency_incident_recipients;
CREATE POLICY "Guardians can select own emergency incident recipient rows"
    ON public.emergency_incident_recipients FOR SELECT
    USING (
        auth.uid() = guardian_user_id
        AND EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.guardian_user_id = auth.uid()
              AND ec.user_id = public.emergency_incident_recipients.sender_user_id
        )
    );

-- Narrowest possible UPDATE policy for paired guardians to acknowledge receipt (DELIVERED)
DROP POLICY IF EXISTS "Guardians can acknowledge delivery on own recipient rows" ON public.emergency_incident_recipients;
CREATE POLICY "Guardians can acknowledge delivery on own recipient rows"
    ON public.emergency_incident_recipients FOR UPDATE
    USING (
        auth.uid() = guardian_user_id
        AND EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.guardian_user_id = auth.uid()
              AND ec.user_id = public.emergency_incident_recipients.sender_user_id
        )
    )
    WITH CHECK (
        auth.uid() = guardian_user_id
        AND delivery_status = 'DELIVERED'
        AND delivered_at IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.guardian_user_id = auth.uid()
              AND ec.user_id = public.emergency_incident_recipients.sender_user_id
        )
    );

-- Trigger enforcing that a guardian updating their recipient row can ONLY modify delivery acknowledgement fields
CREATE OR REPLACE FUNCTION public.enforce_guardian_recipient_ack_update()
RETURNS TRIGGER AS $$
BEGIN
    IF auth.uid() = OLD.guardian_user_id AND auth.uid() IS DISTINCT FROM OLD.sender_user_id THEN
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.incident_id IS DISTINCT FROM OLD.incident_id
           OR NEW.sender_user_id IS DISTINCT FROM OLD.sender_user_id
           OR NEW.guardian_user_id IS DISTINCT FROM OLD.guardian_user_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Guardians may only update delivery acknowledgement fields on emergency_incident_recipients.';
        END IF;
        IF NEW.delivery_status <> 'DELIVERED' OR NEW.delivered_at IS NULL THEN
            RAISE EXCEPTION 'Guardian acknowledgement update must set delivery_status = DELIVERED and delivered_at.';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_enforce_guardian_recipient_ack_update ON public.emergency_incident_recipients;
CREATE TRIGGER trg_enforce_guardian_recipient_ack_update
    BEFORE UPDATE ON public.emergency_incident_recipients
    FOR EACH ROW
    EXECUTE FUNCTION public.enforce_guardian_recipient_ack_update();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'emergency_incident_recipients'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.emergency_incident_recipients;
    END IF;
END $$;

-- ==============================================================================
-- 10. GUARDIAN CHAT MESSAGES TABLE (1-to-1 Chat & Phase 4A Normal Location Sharing)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.guardian_chat_messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    guardian_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    sender_name TEXT NOT NULL DEFAULT '',
    recipient_id TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'sent',
    created_at BIGINT NOT NULL,
    message_type TEXT NOT NULL DEFAULT 'TEXT',
    latitude DOUBLE PRECISION NULL,
    longitude DOUBLE PRECISION NULL,
    location_timestamp BIGINT NULL,
    location_status TEXT NULL
);

ALTER TABLE public.guardian_chat_messages
    ADD COLUMN IF NOT EXISTS message_type TEXT NOT NULL DEFAULT 'TEXT';
ALTER TABLE public.guardian_chat_messages
    ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE public.guardian_chat_messages
    ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE public.guardian_chat_messages
    ADD COLUMN IF NOT EXISTS location_timestamp BIGINT;
ALTER TABLE public.guardian_chat_messages
    ADD COLUMN IF NOT EXISTS location_status TEXT;

NOTIFY pgrst, 'reload schema';

CREATE INDEX IF NOT EXISTS idx_guardian_chat_messages_user_guardian_created
    ON public.guardian_chat_messages(user_id, guardian_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_guardian_chat_messages_conversation_created
    ON public.guardian_chat_messages(conversation_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_guardian_chat_messages_recipient_created
    ON public.guardian_chat_messages(recipient_id, created_at ASC);

ALTER TABLE public.guardian_chat_messages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own or received guardian chat messages" ON public.guardian_chat_messages;
CREATE POLICY "Users can view own or received guardian chat messages"
    ON public.guardian_chat_messages FOR SELECT
    USING (
        auth.uid() = user_id
        OR auth.uid()::text = sender_id
        OR auth.uid()::text = recipient_id
        OR EXISTS (
            SELECT 1 FROM public.emergency_contacts ec
            WHERE ec.user_id = public.guardian_chat_messages.user_id
              AND ec.guardian_user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can insert guardian chat messages" ON public.guardian_chat_messages;
CREATE POLICY "Users can insert guardian chat messages"
    ON public.guardian_chat_messages FOR INSERT
    WITH CHECK (
        auth.uid() = user_id
        OR auth.uid()::text = sender_id
    );

DROP POLICY IF EXISTS "Users can update own or received guardian chat messages" ON public.guardian_chat_messages;
CREATE POLICY "Users can update own or received guardian chat messages"
    ON public.guardian_chat_messages FOR UPDATE
    USING (
        auth.uid() = user_id
        OR auth.uid()::text = sender_id
        OR auth.uid()::text = recipient_id
    );

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'guardian_chat_messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.guardian_chat_messages;
    END IF;
END $$;

-- ==============================================================================
-- 9. AIZA EVIDENCE METADATA TABLE (Secure Evidence Vault)
-- ==============================================================================
CREATE TABLE IF NOT EXISTS public.aiza_evidence (
    id TEXT PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    evidence_type TEXT NOT NULL,
    capture_timestamp BIGINT NOT NULL,
    sos_event_id TEXT,
    mime_type TEXT NOT NULL,
    original_file_size BIGINT NOT NULL DEFAULT 0,
    encrypted_file_size BIGINT NOT NULL DEFAULT 0,
    sha256_original_media TEXT NOT NULL,
    sha256_encrypted_media TEXT NOT NULL,
    signature TEXT NOT NULL,
    public_key_ref TEXT NOT NULL,
    kem_public_key TEXT NOT NULL,
    kem_ciphertext TEXT NOT NULL,
    wrapped_aes_key TEXT NOT NULL,
    aes_gcm_iv TEXT NOT NULL,
    wrap_iv TEXT,
    encryption_algorithm TEXT NOT NULL DEFAULT 'AES-256-GCM / ML-KEM-768 / ML-DSA-65',
    format_version TEXT NOT NULL DEFAULT 'AIZA-PQC-VAULT-V1',
    canonical_manifest TEXT,
    storage_bucket TEXT NOT NULL DEFAULT 'aiza-evidence',
    storage_object_path TEXT,
    sync_state TEXT NOT NULL DEFAULT 'BACKED_UP',
    verification_state TEXT NOT NULL DEFAULT 'UNVERIFIED',
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_aiza_evidence_user_id ON public.aiza_evidence(user_id);
CREATE INDEX IF NOT EXISTS idx_aiza_evidence_capture_ts ON public.aiza_evidence(capture_timestamp DESC);

ALTER TABLE public.aiza_evidence ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own evidence records" ON public.aiza_evidence;
CREATE POLICY "Users can view own evidence records"
    ON public.aiza_evidence FOR SELECT
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert own evidence records" ON public.aiza_evidence;
CREATE POLICY "Users can insert own evidence records"
    ON public.aiza_evidence FOR INSERT
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own evidence records" ON public.aiza_evidence;
CREATE POLICY "Users can update own evidence records"
    ON public.aiza_evidence FOR UPDATE
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete own evidence records" ON public.aiza_evidence;
CREATE POLICY "Users can delete own evidence records"
    ON public.aiza_evidence FOR DELETE
    USING (auth.uid() = user_id);

