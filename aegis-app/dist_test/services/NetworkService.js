"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.networkService = exports.NetworkService = void 0;
const netinfo_1 = __importDefault(require("@react-native-community/netinfo"));
class NetworkService {
    constructor() {
        this.currentStatus = 'ONLINE';
        this.listeners = [];
        this.unsubscribe = null;
        this.init();
    }
    static getInstance() {
        if (!NetworkService.instance) {
            NetworkService.instance = new NetworkService();
        }
        return NetworkService.instance;
    }
    init() {
        this.unsubscribe = netinfo_1.default.addEventListener((state) => {
            const isOnline = !!(state.isConnected && state.isInternetReachable !== false);
            const newStatus = isOnline ? 'ONLINE' : 'OFFLINE';
            if (newStatus !== this.currentStatus) {
                this.currentStatus = newStatus;
                this.notifyListeners(newStatus);
            }
        });
    }
    getStatus() {
        return this.currentStatus;
    }
    isOnline() {
        return this.currentStatus === 'ONLINE';
    }
    isOffline() {
        return this.currentStatus === 'OFFLINE';
    }
    subscribe(listener) {
        this.listeners.push(listener);
        // Emit immediate current status
        listener(this.currentStatus);
        return () => {
            this.listeners = this.listeners.filter((l) => l !== listener);
        };
    }
    notifyListeners(status) {
        for (const listener of this.listeners) {
            try {
                listener(status);
            }
            catch (err) {
                console.error('[NetworkService] Error notifying listener:', err);
            }
        }
    }
    cleanup() {
        if (this.unsubscribe) {
            this.unsubscribe();
            this.unsubscribe = null;
        }
        this.listeners = [];
    }
}
exports.NetworkService = NetworkService;
NetworkService.instance = null;
exports.networkService = NetworkService.getInstance();
