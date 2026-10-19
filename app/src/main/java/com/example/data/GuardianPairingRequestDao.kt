package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianPairingRequestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: GuardianPairingRequestEntity)

    @Query("SELECT * FROM guardian_pairing_requests WHERE id = :id LIMIT 1")
    suspend fun getRequestById(id: String): GuardianPairingRequestEntity?

    @Query(
        """
        SELECT * FROM guardian_pairing_requests
        WHERE emergencyContactId = :emergencyContactId
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatestRequestForContact(emergencyContactId: String): GuardianPairingRequestEntity?

    @Query(
        """
        SELECT * FROM guardian_pairing_requests
        WHERE requesterUserId = :requesterUserId
        ORDER BY createdAt DESC
        """
    )
    fun observeRequestsByRequester(requesterUserId: String): Flow<List<GuardianPairingRequestEntity>>

    @Query(
        """
        SELECT * FROM guardian_pairing_requests
        WHERE LOWER(TRIM(guardianEmail)) = LOWER(TRIM(:guardianEmail))
        ORDER BY createdAt DESC
        """
    )
    fun observeRequestsForGuardianEmail(guardianEmail: String): Flow<List<GuardianPairingRequestEntity>>

    @Query(
        """
        SELECT * FROM guardian_pairing_requests
        WHERE LOWER(TRIM(guardianEmail)) = LOWER(TRIM(:guardianEmail))
          AND UPPER(TRIM(status)) = 'PENDING'
        ORDER BY createdAt DESC
        """
    )
    fun observePendingRequestsForGuardianEmail(guardianEmail: String): Flow<List<GuardianPairingRequestEntity>>

    @Query(
        """
        SELECT * FROM guardian_pairing_requests
        WHERE LOWER(TRIM(guardianEmail)) = LOWER(TRIM(:guardianEmail))
          AND UPPER(TRIM(status)) = 'PENDING'
        ORDER BY createdAt DESC
        """
    )
    suspend fun getPendingRequestsForGuardianEmail(guardianEmail: String): List<GuardianPairingRequestEntity>

    @Query(
        """
        SELECT * FROM guardian_pairing_requests
        ORDER BY createdAt DESC
        """
    )
    fun observeAllRequests(): Flow<List<GuardianPairingRequestEntity>>

    @Query(
        """
        UPDATE guardian_pairing_requests
        SET status = :status, guardianUserId = :guardianUserId
        WHERE id = :id
        """
    )
    suspend fun updateRequestStatus(id: String, status: String, guardianUserId: String?)

    @Query("DELETE FROM guardian_pairing_requests WHERE id = :id")
    suspend fun deleteRequestById(id: String)
}

