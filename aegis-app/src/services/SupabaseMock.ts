/**
 * High-fidelity in-memory mock of Supabase PostgREST & Auth client
 * for comprehensive offline unit tests verifying Phase 3 criteria without hitting live network.
 */
export class SupabaseMock {
  public authUsers: Map<string, any> = new Map();
  public authSessions: Map<string, any> = new Map();
  public activeSessionUser: any = null;
  public otpStore: Map<string, string> = new Map();

  // Database tables
  public profilesTable: Map<string, any> = new Map();
  public emergencyContactsTable: Map<string, any> = new Map();
  public sosAlertsTable: Map<string, any> = new Map();
  public userLocationsTable: Map<string, any> = new Map();
  public realtimeListeners: Map<string, Array<(payload: any) => void>> = new Map();

  public auth = {
    signUp: async ({ email, password, options }: any) => {
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

    signInWithPassword: async ({ email, password }: any) => {
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

    verifyOtp: async ({ email, token }: any) => {
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

    resend: async ({ email }: any) => {
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

    onAuthStateChange: (callback: any) => {
      return {
        data: {
          subscription: {
            unsubscribe: () => {},
          },
        },
      };
    },
  };

  public from(tableName: string) {
    const self = this;
    const filters: Array<{ field: string; value: any }> = [];
    let orderConfig: { field?: string; ascending?: boolean } = {};
    let limitCount: number | null = null;

    const builder: any = {
      select: (cols?: string) => builder,
      eq: (field: string, val: any) => {
        filters.push({ field, value: val });
        return builder;
      },
      order: (field: string, opts?: { ascending?: boolean }) => {
        orderConfig = { field, ascending: opts?.ascending ?? true };
        return builder;
      },
      limit: (cnt: number) => {
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
      upsert: (payload: any) => {
        const id = payload.id || payload.user_id || 'row-' + Math.random().toString(36).substring(2, 9);
        const record = { ...payload, id: payload.id || id };
        self.getTableMap(tableName).set(id, record);

        return {
          select: () => ({
            single: async () => ({ data: record, error: null }),
          }),
        };
      },
      insert: (payload: any) => {
        const id = payload.id || 'row-' + Math.random().toString(36).substring(2, 9);
        const record = { ...payload, id };
        self.getTableMap(tableName).set(id, record);

        return {
          select: () => ({
            single: async () => ({ data: record, error: null }),
          }),
        };
      },
      update: (payload: any) => {
        return {
          eq: (field1: string, val1: any) => ({
            eq: (field2: string, val2: any) => {
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
                then: (resolve: any) => resolve(applyUpdate()),
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
        eq: (field1: string, val1: any) => ({
          eq: async (field2: string, val2: any) => {
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
      then: (resolve: any) => {
        const rows = self.getTableRows(tableName, filters, orderConfig, limitCount);
        resolve({ data: rows, error: null });
      },
    };

    return builder;
  }

  public channel(channelName: string) {
    const self = this;
    const channelObj: any = {
      name: channelName,
      on: (_type: string, _filter: any, callback: (payload: any) => void) => {
        const existing = self.realtimeListeners.get(channelName) || [];
        existing.push(callback);
        self.realtimeListeners.set(channelName, existing);
        return channelObj;
      },
      subscribe: (statusCb?: (status: string) => void) => {
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

  public async removeChannel(channelObj: any) {
    if (channelObj && channelObj.name) {
      this.realtimeListeners.delete(channelObj.name);
    }
    return 'ok';
  }

  public emitRealtimeInsert(tableName: string, row: any) {
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

  private getTableMap(tableName: string): Map<string, any> {
    switch (tableName) {
      case 'profiles':
        return this.profilesTable;
      case 'emergency_contacts':
        return this.emergencyContactsTable;
      case 'sos_alerts':
        return this.sosAlertsTable;
      case 'user_locations':
        return this.userLocationsTable;
      default:
        throw new Error('Unknown mock table: ' + tableName);
    }
  }

  private getTableRows(
    tableName: string,
    filters: Array<{ field: string; value: any }>,
    order: { field?: string; ascending?: boolean },
    limit: number | null
  ): any[] {
    const map = this.getTableMap(tableName);
    let rows = Array.from(map.values());

    if (filters.length > 0) {
      rows = rows.filter((r) => filters.every((f) => r[f.field] === f.value));
    }

    if (order.field) {
      rows.sort((a, b) => {
        const valA = a[order.field!];
        const valB = b[order.field!];
        if (order.ascending) {
          return valA > valB ? 1 : -1;
        } else {
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
