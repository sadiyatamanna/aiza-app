"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SupabaseMock = void 0;
/**
 * High-fidelity in-memory mock of Supabase PostgREST & Auth client
 * for comprehensive offline unit tests verifying Phase 3 criteria without hitting live network.
 */
class SupabaseMock {
    constructor() {
        this.authUsers = new Map();
        this.authSessions = new Map();
        this.activeSessionUser = null;
        this.otpStore = new Map();
        // Database tables
        this.profilesTable = new Map();
        this.emergencyContactsTable = new Map();
        this.sosAlertsTable = new Map();
        this.userLocationsTable = new Map();
        this.guardianChatMessagesTable = new Map();
        this.realtimeListeners = new Map();
        this.auth = {
            signUp: async ({ email, password, options }) => {
                if (!email || !password) {
                    return { data: { user: null, session: null }, error: { message: 'Invalid credentials' } };
                }
                const userId = 'user-mock-' + Math.random().toString(36).substring(2, 9);
                const userObj = {
                    id: userId,
                    email,
                    user_metadata: options?.data || {},
                    created_at: new Date().toISOString(),
                };
                this.authUsers.set(email, { ...userObj, password });
                this.otpStore.set(email, '123456');
                const sessionObj = {
                    access_token: 'mock-jwt-' + userId,
                    user: userObj,
                };
                this.activeSessionUser = userObj;
                this.authSessions.set(userId, sessionObj);
                return {
                    data: { user: userObj, session: sessionObj },
                    error: null,
                };
            },
            signInWithPassword: async ({ email, password }) => {
                const existing = this.authUsers.get(email);
                if (!existing || existing.password !== password) {
                    return { data: { user: null, session: null }, error: { message: 'Invalid login credentials' } };
                }
                const sessionObj = {
                    access_token: 'mock-jwt-' + existing.id,
                    user: existing,
                };
                this.activeSessionUser = existing;
                this.authSessions.set(existing.id, sessionObj);
                return {
                    data: { user: existing, session: sessionObj },
                    error: null,
                };
            },
            verifyOtp: async ({ email, token }) => {
                const validToken = this.otpStore.get(email);
                if (!validToken || validToken !== token) {
                    return { data: { user: null, session: null }, error: { message: 'Invalid or expired OTP token' } };
                }
                const user = this.authUsers.get(email) || { id: 'mock-otp-uid', email };
                const session = { access_token: 'mock-otp-jwt', user };
                this.activeSessionUser = user;
                return {
                    data: { user, session },
                    error: null,
                };
            },
            resend: async ({ email }) => {
                if (!email) {
                    return { error: { message: 'Email required' } };
                }
                this.otpStore.set(email, '654321');
                return { error: null };
            },
            signOut: async () => {
                this.activeSessionUser = null;
                return { error: null };
            },
            getSession: async () => {
                if (!this.activeSessionUser) {
                    return { data: { session: null }, error: null };
                }
                const session = this.authSessions.get(this.activeSessionUser.id) || {
                    access_token: 'mock-jwt',
                    user: this.activeSessionUser,
                };
                return { data: { session }, error: null };
            },
            getUser: async () => {
                return { data: { user: this.activeSessionUser }, error: null };
            },
            onAuthStateChange: (callback) => {
                return {
                    data: {
                        subscription: {
                            unsubscribe: () => { },
                        },
                    },
                };
            },
        };
    }
    from(tableName) {
        const self = this;
        const filters = [];
        let orderConfig = {};
        let limitCount = null;
        const builder = {
            select: (cols) => builder,
            eq: (field, val) => {
                filters.push({ field, value: val });
                return builder;
            },
            order: (field, opts) => {
                orderConfig = { field, ascending: opts?.ascending ?? true };
                return builder;
            },
            limit: (cnt) => {
                limitCount = cnt;
                return builder;
            },
            single: async () => {
                const rows = self.getTableRows(tableName, filters, orderConfig, limitCount);
                if (rows.length === 0) {
                    return { data: null, error: { message: 'Row not found' } };
                }
                return { data: rows[0], error: null };
            },
            maybeSingle: async () => {
                const rows = self.getTableRows(tableName, filters, orderConfig, limitCount);
                return { data: rows.length > 0 ? rows[0] : null, error: null };
            },
            upsert: (payload) => {
                const id = payload.id || payload.user_id || 'row-' + Math.random().toString(36).substring(2, 9);
                const record = { ...payload, id: payload.id || id };
                self.getTableMap(tableName).set(id, record);
                return {
                    select: () => ({
                        single: async () => ({ data: record, error: null }),
                    }),
                };
            },
            insert: (payload) => {
                const id = payload.id || 'row-' + Math.random().toString(36).substring(2, 9);
                const record = { ...payload, id };
                self.getTableMap(tableName).set(id, record);
                return {
                    select: () => ({
                        single: async () => ({ data: record, error: null }),
                    }),
                };
            },
            update: (payload) => {
                return {
                    eq: (field1, val1) => ({
                        eq: (field2, val2) => {
                            const applyUpdate = () => {
                                const map = self.getTableMap(tableName);
                                for (const [k, v] of map.entries()) {
                                    if (v[field1] === val1 && v[field2] === val2) {
                                        const updated = { ...v, ...payload };
                                        map.set(k, updated);
                                        return { data: updated, error: null };
                                    }
                                }
                                return { data: null, error: { message: 'Not found to update' } };
                            };
                            return {
                                select: () => ({
                                    single: async () => applyUpdate(),
                                }),
                                then: (resolve) => resolve(applyUpdate()),
                            };
                        },
                        select: () => ({
                            single: async () => {
                                const map = self.getTableMap(tableName);
                                for (const [k, v] of map.entries()) {
                                    if (v[field1] === val1) {
                                        const updated = { ...v, ...payload };
                                        map.set(k, updated);
                                        return { data: updated, error: null };
                                    }
                                }
                                return { data: null, error: { message: 'Not found to update' } };
                            },
                        }),
                    }),
                };
            },
            delete: () => ({
                eq: (field1, val1) => ({
                    eq: async (field2, val2) => {
                        const map = self.getTableMap(tableName);
                        for (const [k, v] of map.entries()) {
                            if (v[field1] === val1 && v[field2] === val2) {
                                map.delete(k);
                            }
                        }
                        return { error: null };
                    },
                }),
            }),
            then: (resolve) => {
                const rows = self.getTableRows(tableName, filters, orderConfig, limitCount);
                resolve({ data: rows, error: null });
            },
        };
        return builder;
    }
    channel(channelName) {
        const self = this;
        const channelObj = {
            name: channelName,
            on: (_type, _filter, callback) => {
                const existing = self.realtimeListeners.get(channelName) || [];
                existing.push(callback);
                self.realtimeListeners.set(channelName, existing);
                return channelObj;
            },
            subscribe: (statusCb) => {
                if (statusCb) {
                    statusCb('SUBSCRIBED');
                }
                return channelObj;
            },
            unsubscribe: async () => {
                self.realtimeListeners.delete(channelName);
                return 'ok';
            },
        };
        return channelObj;
    }
    async removeChannel(channelObj) {
        if (channelObj && channelObj.name) {
            this.realtimeListeners.delete(channelObj.name);
        }
        return 'ok';
    }
    emitRealtimeInsert(tableName, row) {
        const map = this.getTableMap(tableName);
        if (row.id) {
            map.set(row.id, row);
        }
        for (const listeners of this.realtimeListeners.values()) {
            for (const cb of listeners) {
                cb({
                    schema: 'public',
                    table: tableName,
                    eventType: 'INSERT',
                    new: row,
                    old: {},
                });
            }
        }
    }
    getTableMap(tableName) {
        switch (tableName) {
            case 'profiles':
                return this.profilesTable;
            case 'emergency_contacts':
                return this.emergencyContactsTable;
            case 'sos_alerts':
                return this.sosAlertsTable;
            case 'user_locations':
                return this.userLocationsTable;
            case 'guardian_chat_messages':
                return this.guardianChatMessagesTable;
            default:
                throw new Error('Unknown mock table: ' + tableName);
        }
    }
    getTableRows(tableName, filters, order, limit) {
        const map = this.getTableMap(tableName);
        let rows = Array.from(map.values());
        if (filters.length > 0) {
            rows = rows.filter((r) => filters.every((f) => r[f.field] === f.value));
        }
        if (order.field) {
            rows.sort((a, b) => {
                const valA = a[order.field];
                const valB = b[order.field];
                if (order.ascending) {
                    return valA > valB ? 1 : -1;
                }
                else {
                    return valA < valB ? 1 : -1;
                }
            });
        }
        if (limit !== null) {
            rows = rows.slice(0, limit);
        }
        return rows;
    }
}
exports.SupabaseMock = SupabaseMock;
