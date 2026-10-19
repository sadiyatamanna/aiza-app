package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencyIncidentDao {

    /**
     * Observes the most recent ACTIVE emergency incident for the authenticated [userId].
     */
    @Query(
        """
        SELECT * FROM emergency_incidents
        WHERE userId = :userId AND status = 'ACTIVE'
        ORDER BY startedAt DESC, updatedAt DESC
        LIMIT 1
        """
    )
    fun observeActiveIncident(userId: String): Flow<EmergencyIncidentEntity?>

    /**
     * Returns the current ACTIVE emergency incident for the authenticated [userId], or null if none.
     */
    @Query(
        """
        SELECT * FROM emergency_incidents
        WHERE userId = :userId AND status = 'ACTIVE'
        ORDER BY startedAt DESC, updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getActiveIncidentForUser(userId: String): EmergencyIncidentEntity?

    /**
     * Observes all emergency incidents for the authenticated [userId], ordered newest first.
     */
    @Query(
        """
        SELECT * FROM emergency_incidents
        WHERE userId = :userId
        ORDER BY startedAt DESC, createdAt DESC
        """
    )
    fun observeIncidents(userId: String): Flow<List<EmergencyIncidentEntity>>

    /**
     * Retrieves a single emergency incident by [id], or null if not found.
     */
    @Query(
        """
        SELECT * FROM emergency_incidents
        WHERE id = :id
        LIMIT 1
        """
    )
    suspend fun getIncidentById(id: String): EmergencyIncidentEntity?

    /**
     * Retrieves a single emergency incident by [id] scoped to the authenticated [userId].
     */
    @Query(
        """
        SELECT * FROM emergency_incidents
        WHERE id = :id AND userId = :userId
        LIMIT 1
        """
    )
    suspend fun getIncidentByIdForUser(id: String, userId: String): EmergencyIncidentEntity?

    /**
     * Returns all incidents for the authenticated [userId] that are awaiting cloud sync
     * (`PENDING` or `FAILED`), ordered oldest first.
     */
    @Query(
        """
        SELECT * FROM emergency_incidents
        WHERE userId = :userId AND deliveryStatus IN ('PENDING', 'FAILED')
        ORDER BY startedAt ASC, createdAt ASC
        """
    )
    suspend fun getPendingIncidentsForUser(userId: String): List<EmergencyIncidentEntity>

    /**
     * Inserts or replaces an emergency incident in Room.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: EmergencyIncidentEntity)

    /**
     * Updates an existing emergency incident in Room.
     */
    @Update
    suspend fun updateIncident(incident: EmergencyIncidentEntity): Int

    /**
     * Updates the delivery status and optional error message for an incident owned by [userId].
     */
    @Query(
        """
        UPDATE emergency_incidents
        SET deliveryStatus = :deliveryStatus,
            lastDeliveryError = :lastDeliveryError,
            updatedAt = :updatedAt
        WHERE id = :id AND userId = :userId
        """
    )
    suspend fun updateDeliveryStatus(
        id: String,
        userId: String,
        deliveryStatus: String,
        lastDeliveryError: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Updates the location fields for an incident owned by [userId].
     */
    @Query(
        """
        UPDATE emergency_incidents
        SET latitude = :latitude,
            longitude = :longitude,
            locationTimestamp = :locationTimestamp,
            locationStatus = :locationStatus,
            updatedAt = :updatedAt
        WHERE id = :id AND userId = :userId
        """
    )
    suspend fun updateLocation(
        id: String,
        userId: String,
        latitude: Double?,
        longitude: Double?,
        locationTimestamp: Long?,
        locationStatus: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Updates the audio recording status, duration, and storage path for an incident owned by [userId].
     */
    @Query(
        """
        UPDATE emergency_incidents
        SET audioRecordingStatus = :audioRecordingStatus,
            audioDurationMs = :audioDurationMs,
            audioStoragePath = :audioStoragePath,
            updatedAt = :updatedAt
        WHERE id = :id AND userId = :userId
        """
    )
    suspend fun updateAudioStatus(
        id: String,
        userId: String,
        audioRecordingStatus: String,
        audioDurationMs: Long? = null,
        audioStoragePath: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Marks an incident owned by [userId] as resolved (or cancelled) and sets [endedAt].
     */
    @Query(
        """
        UPDATE emergency_incidents
        SET status = :status,
            endedAt = :endedAt,
            updatedAt = :updatedAt
        WHERE id = :id AND userId = :userId
        """
    )
    suspend fun markResolved(
        id: String,
        userId: String,
        status: String = EmergencyIncidentEntity.STATUS_RESOLVED,
        endedAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Deletes an incident by [id] scoped to the authenticated [userId].
     */
    @Query(
        """
        DELETE FROM emergency_incidents
        WHERE id = :id AND userId = :userId
        """
    )
    suspend fun deleteIncident(id: String, userId: String): Int

    @Query("DELETE FROM emergency_incidents")
    suspend fun deleteAllIncidents(): Int

    /**
     * Deletes an incident entity directly.
     */
    @Delete
    suspend fun deleteIncident(incident: EmergencyIncidentEntity): Int

    // =========================================================================
    // Phase 3: Guardian Received Incidents & Recipient Delivery Tracking
    // =========================================================================

    /**
     * Observes all emergency incidents received for the authenticated [guardianUserId],
     * ordered newest first. Retains the original sender's [EmergencyIncidentEntity.userId]
     * while populating [EmergencyIncidentEntity.recipientGuardianUserId] and guardian delivery status.
     */
    @Query(
        """
        SELECT
            ei.id AS id,
            ei.userId AS userId,
            ei.triggerSource AS triggerSource,
            ei.status AS status,
            ei.startedAt AS startedAt,
            ei.endedAt AS endedAt,
            ei.latitude AS latitude,
            ei.longitude AS longitude,
            ei.locationTimestamp AS locationTimestamp,
            ei.locationStatus AS locationStatus,
            ei.batteryPercent AS batteryPercent,
            ei.networkStatus AS networkStatus,
            ei.medicalSharingEnabled AS medicalSharingEnabled,
            ei.medicalSummary AS medicalSummary,
            ei.audioRecordingStatus AS audioRecordingStatus,
            ei.audioDurationMs AS audioDurationMs,
            ei.audioStoragePath AS audioStoragePath,
            COALESCE(eir.deliveryStatus, ei.deliveryStatus) AS deliveryStatus,
            COALESCE(eir.lastError, ei.lastDeliveryError) AS lastDeliveryError,
            ei.createdAt AS createdAt,
            ei.updatedAt AS updatedAt,
            COALESCE(eir.guardianUserId, ei.recipientGuardianUserId) AS recipientGuardianUserId
        FROM emergency_incidents ei
        LEFT JOIN emergency_incident_recipients eir
            ON ei.id = eir.incidentId AND eir.guardianUserId = :guardianUserId
        WHERE ei.userId != :guardianUserId
          AND (
              (eir.guardianUserId = :guardianUserId AND eir.deliveryStatus = 'DELIVERED')
              OR (eir.id IS NULL AND ei.recipientGuardianUserId = :guardianUserId)
          )
        ORDER BY ei.startedAt DESC, ei.updatedAt DESC
        """
    )
    fun observeReceivedIncidents(guardianUserId: String): Flow<List<EmergencyIncidentEntity>>

    /**
     * Observes currently ACTIVE emergency incidents received for the authenticated [guardianUserId].
     */
    @Query(
        """
        SELECT
            ei.id AS id,
            ei.userId AS userId,
            ei.triggerSource AS triggerSource,
            ei.status AS status,
            ei.startedAt AS startedAt,
            ei.endedAt AS endedAt,
            ei.latitude AS latitude,
            ei.longitude AS longitude,
            ei.locationTimestamp AS locationTimestamp,
            ei.locationStatus AS locationStatus,
            ei.batteryPercent AS batteryPercent,
            ei.networkStatus AS networkStatus,
            ei.medicalSharingEnabled AS medicalSharingEnabled,
            ei.medicalSummary AS medicalSummary,
            ei.audioRecordingStatus AS audioRecordingStatus,
            ei.audioDurationMs AS audioDurationMs,
            ei.audioStoragePath AS audioStoragePath,
            COALESCE(eir.deliveryStatus, ei.deliveryStatus) AS deliveryStatus,
            COALESCE(eir.lastError, ei.lastDeliveryError) AS lastDeliveryError,
            ei.createdAt AS createdAt,
            ei.updatedAt AS updatedAt,
            COALESCE(eir.guardianUserId, ei.recipientGuardianUserId) AS recipientGuardianUserId
        FROM emergency_incidents ei
        LEFT JOIN emergency_incident_recipients eir
            ON ei.id = eir.incidentId AND eir.guardianUserId = :guardianUserId
        WHERE ei.userId != :guardianUserId
          AND ei.status = 'ACTIVE'
          AND (
              (eir.guardianUserId = :guardianUserId AND eir.deliveryStatus = 'DELIVERED')
              OR (eir.id IS NULL AND ei.recipientGuardianUserId = :guardianUserId)
          )
        ORDER BY ei.startedAt DESC, ei.updatedAt DESC
        """
    )
    fun observeActiveReceivedIncidents(guardianUserId: String): Flow<List<EmergencyIncidentEntity>>

    /**
     * Returns all emergency incidents received and persisted for the authenticated [guardianUserId].
     */
    @Query(
        """
        SELECT
            ei.id AS id,
            ei.userId AS userId,
            ei.triggerSource AS triggerSource,
            ei.status AS status,
            ei.startedAt AS startedAt,
            ei.endedAt AS endedAt,
            ei.latitude AS latitude,
            ei.longitude AS longitude,
            ei.locationTimestamp AS locationTimestamp,
            ei.locationStatus AS locationStatus,
            ei.batteryPercent AS batteryPercent,
            ei.networkStatus AS networkStatus,
            ei.medicalSharingEnabled AS medicalSharingEnabled,
            ei.medicalSummary AS medicalSummary,
            ei.audioRecordingStatus AS audioRecordingStatus,
            ei.audioDurationMs AS audioDurationMs,
            ei.audioStoragePath AS audioStoragePath,
            COALESCE(eir.deliveryStatus, ei.deliveryStatus) AS deliveryStatus,
            COALESCE(eir.lastError, ei.lastDeliveryError) AS lastDeliveryError,
            ei.createdAt AS createdAt,
            ei.updatedAt AS updatedAt,
            COALESCE(eir.guardianUserId, ei.recipientGuardianUserId) AS recipientGuardianUserId
        FROM emergency_incidents ei
        LEFT JOIN emergency_incident_recipients eir
            ON ei.id = eir.incidentId AND eir.guardianUserId = :guardianUserId
        WHERE ei.userId != :guardianUserId
          AND (
              (eir.guardianUserId = :guardianUserId AND eir.deliveryStatus = 'DELIVERED')
              OR (eir.id IS NULL AND ei.recipientGuardianUserId = :guardianUserId)
          )
        ORDER BY ei.startedAt DESC, ei.updatedAt DESC
        """
    )
    suspend fun getReceivedIncidentsForGuardian(guardianUserId: String): List<EmergencyIncidentEntity>

    /**
     * Retrieves a single received emergency incident by [incidentId] for the authenticated [guardianUserId].
     */
    @Query(
        """
        SELECT
            ei.id AS id,
            ei.userId AS userId,
            ei.triggerSource AS triggerSource,
            ei.status AS status,
            ei.startedAt AS startedAt,
            ei.endedAt AS endedAt,
            ei.latitude AS latitude,
            ei.longitude AS longitude,
            ei.locationTimestamp AS locationTimestamp,
            ei.locationStatus AS locationStatus,
            ei.batteryPercent AS batteryPercent,
            ei.networkStatus AS networkStatus,
            ei.medicalSharingEnabled AS medicalSharingEnabled,
            ei.medicalSummary AS medicalSummary,
            ei.audioRecordingStatus AS audioRecordingStatus,
            ei.audioDurationMs AS audioDurationMs,
            ei.audioStoragePath AS audioStoragePath,
            COALESCE(eir.deliveryStatus, ei.deliveryStatus) AS deliveryStatus,
            COALESCE(eir.lastError, ei.lastDeliveryError) AS lastDeliveryError,
            ei.createdAt AS createdAt,
            ei.updatedAt AS updatedAt,
            COALESCE(eir.guardianUserId, ei.recipientGuardianUserId) AS recipientGuardianUserId
        FROM emergency_incidents ei
        LEFT JOIN emergency_incident_recipients eir
            ON ei.id = eir.incidentId AND eir.guardianUserId = :guardianUserId
        WHERE ei.id = :incidentId
          AND ei.userId != :guardianUserId
          AND (
              (eir.guardianUserId = :guardianUserId AND eir.deliveryStatus = 'DELIVERED')
              OR (eir.id IS NULL AND ei.recipientGuardianUserId = :guardianUserId)
          )
        LIMIT 1
        """
    )
    suspend fun getReceivedIncidentByIdForGuardian(
        incidentId: String,
        guardianUserId: String
    ): EmergencyIncidentEntity?

    /**
     * Inserts or replaces an incident recipient record (`UNIQUE(incidentId, guardianUserId)`).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncidentRecipient(recipient: EmergencyIncidentRecipientEntity)

    /**
     * Inserts or replaces a list of incident recipient records.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncidentRecipients(recipients: List<EmergencyIncidentRecipientEntity>)

    /**
     * Updates an existing incident recipient record.
     */
    @Update
    suspend fun updateIncidentRecipient(recipient: EmergencyIncidentRecipientEntity): Int

    /**
     * Retrieves the recipient row for a specific `(incidentId, guardianUserId)` pair.
     */
    @Query(
        """
        SELECT * FROM emergency_incident_recipients
        WHERE incidentId = :incidentId AND guardianUserId = :guardianUserId
        LIMIT 1
        """
    )
    suspend fun getRecipientForIncidentAndGuardian(
        incidentId: String,
        guardianUserId: String
    ): EmergencyIncidentRecipientEntity?

    /**
     * Retrieves all recipient rows for a given [incidentId].
     */
    @Query(
        """
        SELECT * FROM emergency_incident_recipients
        WHERE incidentId = :incidentId
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRecipientsForIncident(incidentId: String): List<EmergencyIncidentRecipientEntity>

    /**
     * Observes all recipient rows for a given [incidentId].
     */
    @Query(
        """
        SELECT * FROM emergency_incident_recipients
        WHERE incidentId = :incidentId
        ORDER BY createdAt ASC
        """
    )
    fun observeRecipientsForIncident(incidentId: String): Flow<List<EmergencyIncidentRecipientEntity>>

    /**
     * Retrieves all recipient rows addressed to the authenticated [guardianUserId].
     */
    @Query(
        """
        SELECT * FROM emergency_incident_recipients
        WHERE guardianUserId = :guardianUserId
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    suspend fun getRecipientsForGuardian(guardianUserId: String): List<EmergencyIncidentRecipientEntity>

    /**
     * Observes all recipient rows addressed to the authenticated [guardianUserId].
     */
    @Query(
        """
        SELECT * FROM emergency_incident_recipients
        WHERE guardianUserId = :guardianUserId
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeRecipientsForGuardian(guardianUserId: String): Flow<List<EmergencyIncidentRecipientEntity>>

    /**
     * Updates the delivery status, deliveredAt timestamp, and error for a recipient row.
     */
    @Query(
        """
        UPDATE emergency_incident_recipients
        SET deliveryStatus = :deliveryStatus,
            deliveredAt = :deliveredAt,
            lastError = :lastError,
            updatedAt = :updatedAt
        WHERE incidentId = :incidentId AND guardianUserId = :guardianUserId
        """
    )
    suspend fun updateRecipientDeliveryStatus(
        incidentId: String,
        guardianUserId: String,
        deliveryStatus: String,
        deliveredAt: Long? = null,
        lastError: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
}
