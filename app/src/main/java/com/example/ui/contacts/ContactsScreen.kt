package com.example.ui.contacts

import com.example.data.PhoneUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.LaunchedEffect
import com.example.data.ContactEntity
import com.example.data.GuardianPairingRequestEntity
import com.example.domain.SafetyRepository
import com.example.ui.theme.AlertPulseRed
import com.example.ui.theme.MinimalWhiteBackground
import com.example.ui.theme.MinimalWhiteSurface
import com.example.ui.theme.MagentaPrimary
import com.example.ui.theme.MagentaSoftBg
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.EmeraldDark
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import com.example.ui.theme.CurrentTextPrimary
import com.example.ui.theme.CurrentTextMuted
import com.example.ui.theme.CurrentBorder
import com.example.viewmodel.SafetyViewModel

@Composable
fun ContactsScreen(
    viewModel: SafetyViewModel,
    innerPadding: PaddingValues,
    onNavigateToMap: () -> Unit = {}
) {
    val contacts by viewModel.contacts.collectAsState()
    val count by viewModel.contactCount.collectAsState()
    val verificationMap by viewModel.verificationState.collectAsState()
    val pairingRequests by viewModel.pairingRequests.collectAsState()
    val currentUserEmail by viewModel.userEmail.collectAsState()

    LaunchedEffect(currentUserEmail) {
        viewModel.refreshGuardianPairingRequests()
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<ContactEntity?>(null) }
    var deletingContact by remember { mutableStateOf<ContactEntity?>(null) }

    val latestRequestByContactId = remember(pairingRequests) {
        pairingRequests
            .groupBy { it.emergencyContactId.trim() }
            .mapValues { (_, list) -> list.maxByOrNull { it.createdAt } }
    }

    val incomingPendingRequests = remember(pairingRequests, currentUserEmail) {
        pairingRequests.filter { request ->
            SafetyRepository.isIncomingPendingRequestForGuardian(
                request = request,
                authenticatedGuardianEmail = currentUserEmail
            )
        }
    }

    // Form fields for Add/Edit
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var relationInput by remember { mutableStateOf("") }
    var priorityInput by remember { mutableStateOf("1") }

    var nameError by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf(false) }

    val openAddDialog = {
        nameInput = ""
        phoneInput = ""
        relationInput = ""
        priorityInput = (contacts.size + 1).toString()
        nameError = false
        phoneError = false
        showAddDialog = true
    }

    val openEditDialog = { contact: ContactEntity ->
        nameInput = contact.name
        phoneInput = contact.phone
        relationInput = contact.relation
        priorityInput = contact.priority.ifBlank { "1" }
        nameError = false
        phoneError = false
        editingContact = contact
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalWhiteBackground),
        floatingActionButton = {
            FloatingActionButton(
                onClick = openAddDialog,
                containerColor = MagentaPrimary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 80.dp)
                    .testTag("add_contact_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Contact")
            }
        },
        containerColor = MinimalWhiteBackground
    ) { localPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(16.dp))

                // Title header
                Text(
                    text = "Emergency Contacts",
                    color = CurrentTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "AIZA transmits your real-time location and calls contacts according to priority rank during an SOS.",
                    color = CurrentTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                if (incomingPendingRequests.isNotEmpty()) {
                    IncomingGuardianPairingRequestsSection(
                        requests = incomingPendingRequests,
                        onAccept = { request -> viewModel.acceptGuardianPairingRequest(request.id) },
                        onReject = { request -> viewModel.rejectGuardianPairingRequest(request.id) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Main Contacts list sorted by priority
                val sortedContacts = remember(contacts) {
                    contacts.sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }
                }

                if (sortedContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(CurrentBorder.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContactPhone,
                                    contentDescription = "Empty Contacts",
                                    tint = CurrentTextMuted,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Emergency Contacts Added",
                                color = CurrentTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tap the '+' button below to add emergency contacts.",
                                color = CurrentTextMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(
                            items = sortedContacts,
                            key = { _, item -> if (item.contactId.isNotBlank()) item.contactId else item.id.toString() }
                        ) { index, contact ->
                            ContactItemCard(
                                contact = contact,
                                isFirst = index == 0,
                                isLast = index == sortedContacts.lastIndex,
                                verificationStatus = verificationMap[contact.id],
                                latestPairingRequest = latestRequestByContactId[contact.contactId.trim()],
                                onVerify = { viewModel.verifyContact(contact) },
                                onEdit = { openEditDialog(contact) },
                                onDelete = { deletingContact = contact },
                                onMoveUp = { viewModel.moveContactPriority(contact, moveUp = true) },
                                onMoveDown = { viewModel.moveContactPriority(contact, moveUp = false) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }

            // Dialog for ADDING contact
            if (showAddDialog) {
                ContactFormDialog(
                    title = "Add Emergency Contact",
                    nameInput = nameInput,
                    onNameChange = { nameInput = it; nameError = false },
                    nameError = nameError,
                    phoneInput = phoneInput,
                    onPhoneChange = { phoneInput = it; phoneError = false },
                    phoneError = phoneError,
                    relationInput = relationInput,
                    onRelationChange = { relationInput = it },
                    priorityInput = priorityInput,
                    onPriorityChange = { priorityInput = it },
                    onDismiss = { showAddDialog = false },
                    onSave = {
                        if (nameInput.isBlank()) nameError = true
                        val isPhoneValid = PhoneUtils.isValid(phoneInput)
                        if (!isPhoneValid) phoneError = true

                        if (nameInput.isNotBlank() && isPhoneValid) {
                            viewModel.addContact(
                                name = nameInput.trim(),
                                phone = PhoneUtils.normalize(phoneInput),
                                relation = relationInput.trim().ifBlank { "Emergency Contact" },
                                priority = priorityInput
                            )
                            showAddDialog = false
                        }
                    }
                )
            }

            // Dialog for EDITING contact
            editingContact?.let { target ->
                ContactFormDialog(
                    title = "Edit Emergency Contact",
                    nameInput = nameInput,
                    onNameChange = { nameInput = it; nameError = false },
                    nameError = nameError,
                    phoneInput = phoneInput,
                    onPhoneChange = { phoneInput = it; phoneError = false },
                    phoneError = phoneError,
                    relationInput = relationInput,
                    onRelationChange = { relationInput = it },
                    priorityInput = priorityInput,
                    onPriorityChange = { priorityInput = it },
                    onDismiss = { editingContact = null },
                    onSave = {
                        if (nameInput.isBlank()) nameError = true
                        val isPhoneValid = PhoneUtils.isValid(phoneInput)
                        if (!isPhoneValid) phoneError = true

                        if (nameInput.isNotBlank() && isPhoneValid) {
                            viewModel.updateContact(
                                target.copy(
                                    name = nameInput.trim(),
                                    phone = PhoneUtils.normalize(phoneInput),
                                    relation = relationInput.trim().ifBlank { "Emergency Contact" },
                                    priority = priorityInput
                                )
                            )
                            editingContact = null
                        }
                    }
                )
            }

            // Confirmation Dialog for DELETING contact
            deletingContact?.let { contactToDelete ->
                AlertDialog(
                    onDismissRequest = { deletingContact = null },
                    containerColor = MinimalWhiteSurface,
                    title = {
                        Text(
                            text = "Delete Emergency Contact",
                            color = CurrentTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to delete '${contactToDelete.name}' (${contactToDelete.phone})? They will no longer be notified during SOS emergencies.",
                            color = CurrentTextMuted,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteContact(contactToDelete)
                                deletingContact = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AlertPulseRed)
                        ) {
                            Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deletingContact = null }) {
                            Text("Cancel", color = CurrentTextMuted)
                        }
                    }
                )
            }
        }
    }
}



@Composable
fun IncomingGuardianPairingRequestsSection(
    requests: List<GuardianPairingRequestEntity>,
    onAccept: (GuardianPairingRequestEntity) -> Unit,
    onReject: (GuardianPairingRequestEntity) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("incoming_pairing_requests_section")
    ) {
        Text(
            text = "Guardian Requests",
            color = CurrentTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("guardian_requests_header")
        )

        requests.forEach { request ->
            val displayName = when {
                request.contactName.isNotBlank() && request.requesterName.isNotBlank() &&
                    !request.contactName.equals(request.requesterName, ignoreCase = true) ->
                    "${request.contactName} (from ${request.requesterName})"
                request.contactName.isNotBlank() -> request.contactName
                request.requesterName.isNotBlank() -> request.requesterName
                else -> "AIZA Guardian Request"
            }
            val displayRelationship = request.relationship.ifBlank { "Emergency Contact" }
            val displayStatus = request.status.trim().uppercase().ifBlank { "PENDING" }

            Card(
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("incoming_pairing_request_${request.id}")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName,
                            color = CurrentTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pairing_request_name_${request.id}")
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("pairing_request_status_${request.id}")
                        ) {
                            Text(
                                text = displayStatus,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Relationship: $displayRelationship",
                        color = CurrentTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("pairing_request_relationship_${request.id}")
                    )
                    Text(
                        text = "Guardian Email: ${request.guardianEmail}",
                        color = CurrentTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("pairing_request_email_${request.id}")
                    )
                    if (request.guardianPhone.isNotBlank()) {
                        Text(
                            text = "Guardian Phone: ${request.guardianPhone}",
                            color = CurrentTextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag("pairing_request_phone_${request.id}")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onAccept(request) },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("accept_pairing_${request.id}")
                        ) {
                            Text("Accept", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { onReject(request) },
                            modifier = Modifier.testTag("reject_pairing_${request.id}")
                        ) {
                            Text("Reject", color = CurrentTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactFormDialog(
    title: String,
    nameInput: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    phoneInput: String,
    onPhoneChange: (String) -> Unit,
    phoneError: Boolean,
    relationInput: String,
    onRelationChange: (String) -> Unit,
    priorityInput: String,
    onPriorityChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MinimalWhiteSurface,
        title = {
            Text(
                text = title,
                color = CurrentTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Name Input
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = onNameChange,
                    label = { Text("Contact Name") },
                    placeholder = { Text("e.g. Sarah / Mother") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Name Icon", tint = MaterialTheme.colorScheme.primary) },
                    isError = nameError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CurrentTextPrimary,
                        unfocusedTextColor = CurrentTextPrimary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = CurrentBorder,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = CurrentTextMuted
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_name_input"),
                    singleLine = true
                )
                if (nameError) {
                    Text("Contact name is required.", color = Color.Red, fontSize = 11.sp)
                }

                // Phone Input (+91)
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = onPhoneChange,
                    label = { Text("Phone Number (+91)") },
                    placeholder = { Text("+91 9876543210") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = "Phone Icon", tint = MaterialTheme.colorScheme.primary) },
                    isError = phoneError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CurrentTextPrimary,
                        unfocusedTextColor = CurrentTextPrimary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = CurrentBorder,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = CurrentTextMuted
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_phone_input"),
                    singleLine = true
                )
                if (phoneError) {
                    Text(
                        text = if (phoneInput.isBlank()) "Phone number is required." else "Must be +91 followed by exactly 10 digits.",
                        color = Color.Red,
                        fontSize = 11.sp
                    )
                }

                // Relationship Input
                OutlinedTextField(
                    value = relationInput,
                    onValueChange = onRelationChange,
                    label = { Text("Relationship") },
                    placeholder = { Text("e.g. Mother, Father, Friend") },
                    leadingIcon = { Icon(Icons.Default.ContactPhone, contentDescription = "Relation Icon", tint = MaterialTheme.colorScheme.primary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CurrentTextPrimary,
                        unfocusedTextColor = CurrentTextPrimary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = CurrentBorder,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = CurrentTextMuted
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_relation_input"),
                    singleLine = true
                )

                // Priority Rank Selection
                Text(
                    text = "Priority Rank (1 = Highest Priority):",
                    color = CurrentTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("1", "2", "3", "4", "5").forEach { rank ->
                        val isSelected = priorityInput == rank
                        val chipBg = if (isSelected) MaterialTheme.colorScheme.primary else CurrentBorder.copy(alpha = 0.2f)
                        val chipContent = if (isSelected) Color.White else CurrentTextMuted
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(chipBg)
                                .clickable { onPriorityChange(rank) }
                                .padding(vertical = 8.dp)
                                .testTag("priority_chip_$rank"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Rank $rank",
                                color = chipContent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Save Contact", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CurrentTextMuted)
            }
        }
    )
}

@Composable
fun ContactItemCard(
    contact: ContactEntity,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    verificationStatus: String?,
    latestPairingRequest: GuardianPairingRequestEntity? = null,
    onVerify: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {

    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contact_card_${contact.contactId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // TOP ROW: Avatar + Name & Relation + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left block: Avatar + Name & Relation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Avatar Circle
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.name.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = contact.name,
                                color = CurrentTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (contact.isVerified) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = "Verified Icon",
                                    tint = EmeraldDark,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${contact.relation.ifBlank { "Emergency Contact" }} • ${contact.phone}",
                            color = CurrentTextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Far Right: Security Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (contact.isVerified) EmeraldDark.copy(alpha = 0.12f)
                            else CurrentBorder.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (contact.isVerified) Icons.Default.CheckCircle else Icons.Default.Lock,
                            contentDescription = "Lock Status",
                            tint = if (contact.isVerified) EmeraldDark else CurrentTextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (contact.isVerified) "Secured E2EE" else "Offline Pending",
                            color = if (contact.isVerified) EmeraldDark else CurrentTextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = CurrentBorder.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // BOTTOM ROW: Priority Badge & Keys on Left, Uniform Action Toolbar (Up, Down, Edit, Delete) on Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Priority Rank Badge & Key Exchange
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Priority",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Priority ${contact.priority.ifBlank { "1" }}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!contact.isVerified && verificationStatus == null) {
                        TextButton(
                            onClick = onVerify,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Exchange Keys", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (verificationStatus != null) {
                        Text(
                            text = verificationStatus,
                            color = if (verificationStatus.contains("Verified")) EmeraldDark else MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Uniform Action Toolbar: Up, Down, Edit, Delete
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (!isFirst) {
                        IconButton(
                            onClick = onMoveUp,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("move_up_${contact.contactId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Increase Priority",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (!isLast) {
                        IconButton(
                            onClick = onMoveDown,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("move_down_${contact.contactId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Decrease Priority",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("edit_contact_${contact.contactId}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Contact",
                            tint = CurrentTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("delete_contact_${contact.contactId}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Contact",
                            tint = AlertPulseRed.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

