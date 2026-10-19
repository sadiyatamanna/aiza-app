import NetInfo, { NetInfoState } from '@react-native-community/netinfo';

export type NetworkStatus = 'ONLINE' | 'OFFLINE';

export class NetworkService {
  private static instance: NetworkService | null = null;
  private currentStatus: NetworkStatus = 'ONLINE';
  private listeners: ((status: NetworkStatus) => void)[] = [];
  private unsubscribe: (() => void) | null = null;

  private constructor() {
    this.init();
  }

  public static getInstance(): NetworkService {
    if (!NetworkService.instance) {
      NetworkService.instance = new NetworkService();
    }
    return NetworkService.instance;
  }

  private init(): void {
    this.unsubscribe = NetInfo.addEventListener((state: NetInfoState) => {
      const isOnline = !!(state.isConnected && state.isInternetReachable !== false);
      const newStatus: NetworkStatus = isOnline ? 'ONLINE' : 'OFFLINE';
      if (newStatus !== this.currentStatus) {
        this.currentStatus = newStatus;
        this.notifyListeners(newStatus);
      }
    });
  }

  public getStatus(): NetworkStatus {
    return this.currentStatus;
  }

  public isOnline(): boolean {
    return this.currentStatus === 'ONLINE';
  }

  public isOffline(): boolean {
    return this.currentStatus === 'OFFLINE';
  }

  public subscribe(listener: (status: NetworkStatus) => void): () => void {
    this.listeners.push(listener);
    // Emit immediate current status
    listener(this.currentStatus);

    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  private notifyListeners(status: NetworkStatus): void {
    for (const listener of this.listeners) {
      try {
        listener(status);
      } catch (err) {
        console.error('[NetworkService] Error notifying listener:', err);
      }
    }
  }

  public cleanup(): void {
    if (this.unsubscribe) {
      this.unsubscribe();
      this.unsubscribe = null;
    }
    this.listeners = [];
  }
}

export const networkService = NetworkService.getInstance();
