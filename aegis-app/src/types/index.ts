export interface UserProfile {
  id: string;
  email: string;
  name: string;
  phone: string;
  createdAt: number;
  updatedAt: number;
}

export type GuardianConnectionStatus = 'ONLINE' | 'OFFLINE' | 'STALE' | 'STANDBY' | 'UNKNOWN';

export interface EmergencyContact {
  id: string;
  userId: string;
  name: string;
  phoneNumber: string;
  relationship: string;
  priorityOrder: number; // 1 = Direct Voice Call, 2+ = SMS Recipient
  isVerified: boolean;
  avatarUrl?: string;
  createdAt: number;
  isActive?: boolean;
  isOnline?: boolean;
  lastKnownLatitude?: number | null;
  lastKnownLongitude?: number | null;
  lastLocationUpdate?: number | null;
  batteryLevel?: number | null;
  connectionStatus?: GuardianConnectionStatus;
  locationSharingAllowed?: boolean;
}

export type SosTriggerSource = 'MANUAL' | 'SHAKE' | 'VOICE' | 'BLE';

export type SosSyncStatus = 'Sent' | 'Pending_Sync' | 'Failed';

export interface SosAlert {
  id: string;
  userId: string;
  triggerSource: SosTriggerSource;
  latitude: number;
  longitude: number;
  isLastKnownLocation: boolean;
  accuracy: number;
  timestamp: number;
  syncStatus: SosSyncStatus;
  voiceClipUrl?: string | null;
  voiceClipPath?: string | null;
  encryptedPayload?: string | null;
  signature?: string | null;
}

export interface TrackedLocation {
  id: string;
  userId: string;
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude?: number;
  speed?: number;
  bearing?: number;
  batteryLevel?: number;
  isSharingEnabled: boolean;
  timestamp: number;
  isSynced: boolean;
}

export type PlaceCategory = 'POLICE' | 'HOSPITAL' | 'PHARMACY' | 'SHELTER' | 'SAFE_HAVEN';

export interface CachedPlace {
  id: string;
  name: string;
  category: PlaceCategory;
  latitude: number;
  longitude: number;
  address: string;
  phoneNumber?: string;
  distanceMeters?: number;
  cachedAt: number;
}

export interface SafeZone {
  id: string;
  userId: string;
  name: string;
  latitude: number;
  longitude: number;
  radiusMeters: number;
  isActive: boolean;
  createdAt: number;
}

export interface DistressClip {
  id: string;
  localPath: string;
  durationSeconds: number;
  fileSizeBytes: number;
  capturedAt: number;
  uploadStatus: 'LOCAL' | 'UPLOADING' | 'UPLOADED' | 'FAILED';
  publicOrSignedUrl?: string | null;
}
