package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ContactEntity::class,
        SosLogEntity::class,
        TrackedLocationEntity::class,
        CachedPlaceEntity::class,
        GuardianChatMessageEntity::class,
        EmergencyIncidentEntity::class,
        EmergencyIncidentRecipientEntity::class,
        GuardianPairingRequestEntity::class,
        com.example.data.evidence.EvidenceRecordEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun sosLogDao(): SosLogDao
    abstract fun trackedLocationDao(): TrackedLocationDao
    abstract fun cachedPlaceDao(): CachedPlaceDao
    abstract fun guardianChatDao(): GuardianChatDao
    abstract fun guardianPairingRequestDao(): GuardianPairingRequestDao
    abstract fun emergencyIncidentDao(): EmergencyIncidentDao
    abstract fun evidenceDao(): com.example.data.evidence.EvidenceDao

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE emergency_contacts ADD COLUMN guardianUserId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `guardian_pairing_requests` (
                        `id` TEXT NOT NULL,
                        `requesterUserId` TEXT NOT NULL,
                        `guardianEmail` TEXT NOT NULL,
                        `emergencyContactId` TEXT NOT NULL,
                        `guardianUserId` TEXT,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_guardian_pairing_requests_requesterUserId_emergencyContactId` ON `guardian_pairing_requests` (`requesterUserId`, `emergencyContactId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_guardian_pairing_requests_guardianEmail_status` ON `guardian_pairing_requests` (`guardianEmail`, `status`)"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `guardian_pairing_requests` ADD COLUMN `requesterName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `guardian_pairing_requests` ADD COLUMN `contactName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `guardian_pairing_requests` ADD COLUMN `relationship` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `guardian_pairing_requests` ADD COLUMN `guardianPhone` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `emergency_incidents` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `triggerSource` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER,
                        `latitude` REAL,
                        `longitude` REAL,
                        `locationTimestamp` INTEGER,
                        `locationStatus` TEXT,
                        `batteryPercent` INTEGER,
                        `networkStatus` TEXT,
                        `medicalSharingEnabled` INTEGER NOT NULL,
                        `medicalSummary` TEXT,
                        `audioRecordingStatus` TEXT NOT NULL,
                        `audioDurationMs` INTEGER,
                        `audioStoragePath` TEXT,
                        `deliveryStatus` TEXT NOT NULL,
                        `lastDeliveryError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emergency_incidents_userId` ON `emergency_incidents` (`userId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emergency_incidents_startedAt` ON `emergency_incidents` (`startedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emergency_incidents_deliveryStatus` ON `emergency_incidents` (`deliveryStatus`)"
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `emergency_incidents` ADD COLUMN `recipientGuardianUserId` TEXT DEFAULT NULL"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emergency_incidents_recipientGuardianUserId` ON `emergency_incidents` (`recipientGuardianUserId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `emergency_incident_recipients` (
                        `id` TEXT NOT NULL,
                        `incidentId` TEXT NOT NULL,
                        `senderUserId` TEXT NOT NULL,
                        `guardianUserId` TEXT NOT NULL,
                        `deliveryStatus` TEXT NOT NULL,
                        `deliveredAt` INTEGER,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_emergency_incident_recipients_incidentId_guardianUserId` ON `emergency_incident_recipients` (`incidentId`, `guardianUserId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emergency_incident_recipients_incidentId` ON `emergency_incident_recipients` (`incidentId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emergency_incident_recipients_guardianUserId` ON `emergency_incident_recipients` (`guardianUserId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emergency_incident_recipients_deliveryStatus` ON `emergency_incident_recipients` (`deliveryStatus`)"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `guardian_chat_messages` ADD COLUMN `messageType` TEXT NOT NULL DEFAULT 'TEXT'"
                )
                db.execSQL(
                    "ALTER TABLE `guardian_chat_messages` ADD COLUMN `latitude` REAL DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE `guardian_chat_messages` ADD COLUMN `longitude` REAL DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE `guardian_chat_messages` ADD COLUMN `locationTimestamp` INTEGER DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE `guardian_chat_messages` ADD COLUMN `locationStatus` TEXT DEFAULT NULL"
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `evidence_records` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `evidenceType` TEXT NOT NULL,
                        `captureTimestamp` INTEGER NOT NULL,
                        `sosEventId` TEXT,
                        `mimeType` TEXT NOT NULL,
                        `originalFileSize` INTEGER NOT NULL,
                        `encryptedFileSize` INTEGER NOT NULL,
                        `sha256OriginalMedia` TEXT NOT NULL,
                        `sha256EncryptedMedia` TEXT NOT NULL,
                        `signatureBase64` TEXT NOT NULL,
                        `mlDsaPublicKeyHex` TEXT NOT NULL,
                        `mlKemPublicKeyHex` TEXT NOT NULL,
                        `kemCiphertextHex` TEXT NOT NULL,
                        `wrappedAesKeyHex` TEXT NOT NULL,
                        `wrapIvHex` TEXT NOT NULL,
                        `aesGcmIvHex` TEXT NOT NULL,
                        `encryptionAlgorithm` TEXT NOT NULL,
                        `formatVersion` TEXT NOT NULL,
                        `canonicalManifestJson` TEXT NOT NULL,
                        `localEncryptedPath` TEXT,
                        `storageBucket` TEXT NOT NULL,
                        `storageObjectPath` TEXT,
                        `syncState` TEXT NOT NULL,
                        `verificationState` TEXT NOT NULL,
                        `lastSyncError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_records_userId` ON `evidence_records` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_records_sosEventId` ON `evidence_records` (`sosEventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_records_captureTimestamp` ON `evidence_records` (`captureTimestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_records_syncState` ON `evidence_records` (`syncState`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aegis_safety_database"
                )
                .addMigrations(
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

