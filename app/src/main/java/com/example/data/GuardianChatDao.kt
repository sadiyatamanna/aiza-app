package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianChatDao {

    /**
     * Observes all messages for a canonical guardian UUID, ordered oldest to newest.
     * Matches by canonical [guardianId] so session userId reconciliation never hides active messages.
     */
    @Query(
        """
        SELECT * FROM guardian_chat_messages
        WHERE guardianId = :guardianId AND (:userId = '' OR userId = :userId OR guardianId != '')
        ORDER BY createdAt ASC, id ASC
        """
    )
    fun observeMessagesForGuardian(
        userId: String,
        guardianId: String
    ): Flow<List<GuardianChatMessageEntity>>

    /**
     * Reconciles local messages for a guardian to the canonical authenticated Supabase user UUID.
     */
    @Query(
        """
        UPDATE guardian_chat_messages
        SET userId = :userId,
            senderId = CASE WHEN senderId = userId THEN :userId ELSE senderId END,
            conversationId = 'conv_' || :userId || '_' || guardianId
        WHERE guardianId = :guardianId AND userId != :userId
        """
    )
    suspend fun reconcileGuardianMessagesUserId(guardianId: String, userId: String)

    /**
     * Observes all messages for a deterministic conversation ID, ordered oldest to newest.
     */
    @Query(
        """
        SELECT * FROM guardian_chat_messages
        WHERE conversationId = :conversationId
        ORDER BY createdAt ASC, id ASC
        """
    )
    fun observeMessagesForConversation(
        conversationId: String
    ): Flow<List<GuardianChatMessageEntity>>

    /**
     * Returns a one-shot list of messages for a user and guardian ordered oldest to newest.
     */
    @Query(
        """
        SELECT * FROM guardian_chat_messages
        WHERE guardianId = :guardianId AND (:userId = '' OR userId = :userId OR guardianId != '')
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getMessagesForGuardianOnce(
        userId: String,
        guardianId: String
    ): List<GuardianChatMessageEntity>

    /**
     * Inserts or replaces a chat message in local Room storage.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: GuardianChatMessageEntity)

    /**
     * Inserts a chat message only if its ID does not already exist in Room.
     * Returns -1L if the message already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: GuardianChatMessageEntity): Long

    /**
     * Retrieves a single message by ID, or null if not found.
     */
    @Query("SELECT * FROM guardian_chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): GuardianChatMessageEntity?

    /**
     * Checks whether a message with the given ID already exists in Room.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM guardian_chat_messages WHERE id = :id)")
    suspend fun messageExists(id: String): Boolean

    /**
     * Returns all unsynced outgoing messages ordered oldest to newest for reconnection sync.
     */
    @Query(
        """
        SELECT * FROM guardian_chat_messages
        WHERE isSynced = 0
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getPendingMessages(): List<GuardianChatMessageEntity>

    /**
     * Updates the delivery/sync status of a message after a Supabase sync attempt.
     */
    @Query(
        """
        UPDATE guardian_chat_messages
        SET status = :status, isSynced = :isSynced
        WHERE id = :id
        """
    )
    suspend fun updateMessageSyncStatus(id: String, status: String, isSynced: Boolean)
}
