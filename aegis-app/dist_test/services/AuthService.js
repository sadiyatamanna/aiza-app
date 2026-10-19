"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.authService = exports.AuthService = void 0;
const SupabaseClient_1 = require("./SupabaseClient");
class AuthService {
    constructor() { }
    static getInstance() {
        if (!AuthService.instance) {
            AuthService.instance = new AuthService();
        }
        return AuthService.instance;
    }
    /**
     * Email and password registration with optional user metadata (name, phone)
     */
    async signUp(email, password, metadata) {
        try {
            const trimmedEmail = email.trim();
            const { data, error } = await SupabaseClient_1.supabase.auth.signUp({
                email: trimmedEmail,
                password,
                options: {
                    data: {
                        name: metadata?.name?.trim() || '',
                        phone: metadata?.phone?.trim() || '',
                    },
                },
            });
            if (error) {
                return { success: false, error: error.message };
            }
            return {
                success: true,
                user: data.user,
                session: data.session,
            };
        }
        catch (err) {
            return {
                success: false,
                error: err?.message || 'An unexpected error occurred during sign up',
            };
        }
    }
    /**
     * Email and password login
     */
    async signIn(email, password) {
        try {
            const trimmedEmail = email.trim();
            const { data, error } = await SupabaseClient_1.supabase.auth.signInWithPassword({
                email: trimmedEmail,
                password,
            });
            if (error) {
                return { success: false, error: error.message };
            }
            return {
                success: true,
                user: data.user,
                session: data.session,
            };
        }
        catch (err) {
            return {
                success: false,
                error: err?.message || 'An unexpected error occurred during sign in',
            };
        }
    }
    /**
     * Verify email OTP code for signup verification
     */
    async verifyEmailOtp(email, token, type = 'signup') {
        try {
            const trimmedEmail = email.trim();
            const trimmedToken = token.trim();
            const { data, error } = await SupabaseClient_1.supabase.auth.verifyOtp({
                email: trimmedEmail,
                token: trimmedToken,
                type,
            });
            if (error) {
                return { success: false, error: error.message };
            }
            return {
                success: true,
                user: data.user,
                session: data.session,
            };
        }
        catch (err) {
            return {
                success: false,
                error: err?.message || 'Verification token invalid or expired',
            };
        }
    }
    /**
     * Resend verification email/OTP
     */
    async resendVerification(email, type = 'signup') {
        try {
            const trimmedEmail = email.trim();
            const { error } = await SupabaseClient_1.supabase.auth.resend({
                type,
                email: trimmedEmail,
            });
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true };
        }
        catch (err) {
            return {
                success: false,
                error: err?.message || 'Failed to resend verification code',
            };
        }
    }
    /**
     * Sign out current user and clear session
     */
    async signOut() {
        try {
            const { error } = await SupabaseClient_1.supabase.auth.signOut();
            if (error) {
                return { success: false, error: error.message };
            }
            return { success: true };
        }
        catch (err) {
            return {
                success: false,
                error: err?.message || 'Failed to sign out',
            };
        }
    }
    /**
     * Restore and retrieve the current active Supabase session
     */
    async getCurrentSession() {
        try {
            const { data, error } = await SupabaseClient_1.supabase.auth.getSession();
            if (error || !data.session) {
                return null;
            }
            return data.session;
        }
        catch (err) {
            console.warn('[AuthService] Could not retrieve session:', err);
            return null;
        }
    }
    /**
     * Retrieve the current authenticated Supabase user
     */
    async getCurrentUser() {
        try {
            const { data, error } = await SupabaseClient_1.supabase.auth.getUser();
            if (error || !data.user) {
                return null;
            }
            return data.user;
        }
        catch (err) {
            console.warn('[AuthService] Could not retrieve user:', err);
            return null;
        }
    }
    /**
     * Subscribe to Supabase authentication state changes
     */
    onAuthStateChange(callback) {
        const { data: authListener } = SupabaseClient_1.supabase.auth.onAuthStateChange((event, session) => {
            callback(event, session);
        });
        return {
            unsubscribe: () => {
                authListener?.subscription.unsubscribe();
            },
        };
    }
}
exports.AuthService = AuthService;
AuthService.instance = null;
exports.authService = AuthService.getInstance();
