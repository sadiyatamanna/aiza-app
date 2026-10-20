package com.example.ui.auth

import com.example.data.PhoneUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AlertPulseRed
import com.example.ui.theme.BlueGradientEnd
import com.example.ui.theme.BlueGradientStart
import com.example.ui.theme.DeepObsidianBackground
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.GlassySurfaceDark
import com.example.ui.theme.MinimalWhiteSurface
import com.example.ui.theme.CurrentTextPrimary
import com.example.ui.theme.CurrentTextMuted
import com.example.ui.theme.CurrentBorder
import com.example.viewmodel.SafetyViewModel

@Composable
fun AuthScreen(
    viewModel: SafetyViewModel
) {
    var isLoginMode by remember { mutableStateOf(true) }
    val context = LocalContext.current

    // User Fields
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // User's Own Phone Number (Separate from emergency contact)
    var userPhone by remember { mutableStateOf("") }
    var userCountryCode by remember { mutableStateOf("+91") }

    // Single Emergency Contact Fields
    var emergencyName by remember { mutableStateOf("") }
    var emergencyRelation by remember { mutableStateOf("") }
    var emergencyPhone by remember { mutableStateOf("") }

    // Errors
    var nameError by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var userPhoneError by remember { mutableStateOf(false) }
    var emergencyNameError by remember { mutableStateOf(false) }
    var emergencyPhoneError by remember { mutableStateOf(false) }

    // Email Confirmation Dialog State
    var showEmailConfirmationDialog by remember { mutableStateOf(false) }
    var emailConfirmationTarget by remember { mutableStateOf("") }
    var emailConfirmationOtp by remember { mutableStateOf("") }
    var isVerifyingOtp by remember { mutableStateOf(false) }
    var isResendingEmail by remember { mutableStateOf(false) }

    // Forgot Password Dialog State
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var forgotPasswordEmail by remember { mutableStateOf("") }
    var forgotPasswordEmailError by remember { mutableStateOf(false) }
    var isSendingPasswordReset by remember { mutableStateOf(false) }

    fun isValidEmailAddress(input: String): Boolean {
        return com.example.domain.AuthRepository.isValidGmailAddress(input)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidianBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // AIZA Security Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(BlueGradientStart, BlueGradientEnd)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Shield Logo",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "AIZA SECURE PORTAL",
                    color = CurrentTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Supabase Distributed Security Architecture",
                    color = CurrentTextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Switch Mode Tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassySurfaceDark)
                    .border(1.dp, CurrentBorder, RoundedCornerShape(14.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isLoginMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { isLoginMode = true }
                        .testTag("tab_login_mode"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "LOGIN",
                        color = if (isLoginMode) Color.White else CurrentTextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (!isLoginMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { isLoginMode = false }
                        .testTag("tab_register_mode"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "REGISTER",
                        color = if (!isLoginMode) Color.White else CurrentTextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Auth Input Fields Card
            Card(
                colors = CardDefaults.cardColors(containerColor = GlassySurfaceDark),
                border = BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    if (!isLoginMode) {
                        // Full Name field for registration
                        Text(
                            text = "Full Name",
                            color = CurrentTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; nameError = false },
                            placeholder = { Text("Enter your full name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = "User Icon", tint = MaterialTheme.colorScheme.primary) },
                            isError = nameError,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("auth_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CurrentTextPrimary,
                                unfocusedTextColor = CurrentTextPrimary,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = CurrentBorder
                            )
                        )
                        if (nameError) {
                            Text("Full Name is required.", color = Color.Red, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Email Address field (Email authentication only)
                    Text(
                        text = "Email Address",
                        color = CurrentTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; emailError = false },
                        placeholder = { Text("user@gmail.com") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = "Email Icon", tint = MaterialTheme.colorScheme.primary) },
                        isError = emailError,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("auth_email_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CurrentTextPrimary,
                            unfocusedTextColor = CurrentTextPrimary,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = CurrentBorder
                        )
                    )
                    if (emailError) {
                        Text(
                            text = com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE,
                            color = Color.Red,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .testTag("auth_email_error")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Password field
                    Text(
                        text = "Secure Password",
                        color = CurrentTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; passwordError = false },
                        placeholder = { Text("Minimum 6 characters") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Password Icon", tint = MaterialTheme.colorScheme.primary) },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = passwordError,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("auth_password_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CurrentTextPrimary,
                            unfocusedTextColor = CurrentTextPrimary,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = CurrentBorder
                        )
                    )
                    if (passwordError) {
                        Text("Password must be at least 6 characters.", color = Color.Red, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }

                    if (isLoginMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                forgotPasswordEmail = email.trim()
                                forgotPasswordEmailError = false
                                showForgotPasswordDialog = true
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .testTag("auth_forgot_password_button")
                        ) {
                            Text(
                                text = "Forgot Password?",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // User's Own Phone Number Registration Field
                    if (!isLoginMode) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = CurrentBorder)
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "YOUR PHONE NUMBER",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "Your personal phone number, distinct from your emergency contact. Used for direct dispatch coordination.",
                            color = CurrentTextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            OutlinedTextField(
                                value = userCountryCode,
                                onValueChange = { userCountryCode = it },
                                label = { Text("Code") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .width(85.dp)
                                    .testTag("auth_user_country_code"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = CurrentTextPrimary,
                                    unfocusedTextColor = CurrentTextPrimary,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = CurrentBorder
                                )
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = userPhone,
                                    onValueChange = {
                                        userPhone = it
                                        userPhoneError = false
                                    },
                                    placeholder = { Text("9876543210") },
                                    label = { Text("Your Phone Number") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PhoneIphone,
                                            contentDescription = "Your Phone Number",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    isError = userPhoneError,
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_user_phone"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = CurrentTextPrimary,
                                        unfocusedTextColor = CurrentTextPrimary,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = CurrentBorder
                                    )
                                )
                                if (userPhoneError) {
                                    Text(
                                        text = "Please enter a valid phone number (7-15 digits).",
                                        color = Color.Red,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Emergency Contact Registration Field (Strictly ONE contact)
                    if (!isLoginMode) {
                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = CurrentBorder)
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "EMERGENCY CONTACT",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Text(
                            text = "Contact Name",
                            color = CurrentTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        OutlinedTextField(
                            value = emergencyName,
                            onValueChange = { emergencyName = it; emergencyNameError = false },
                            placeholder = { Text("e.g. Sarah / Mother") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Emergency Contact Name", tint = MaterialTheme.colorScheme.primary) },
                            isError = emergencyNameError,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("auth_emergency_name"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CurrentTextPrimary,
                                unfocusedTextColor = CurrentTextPrimary,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = CurrentBorder
                            )
                        )
                        if (emergencyNameError) {
                            Text("Emergency contact name is required.", color = Color.Red, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Relationship",
                            color = CurrentTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        OutlinedTextField(
                            value = emergencyRelation,
                            onValueChange = { emergencyRelation = it },
                            placeholder = { Text("e.g. Mother") },
                            leadingIcon = { Icon(Icons.Default.ContactPhone, contentDescription = "Emergency Contact Relation", tint = MaterialTheme.colorScheme.primary) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("auth_emergency_relation"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CurrentTextPrimary,
                                unfocusedTextColor = CurrentTextPrimary,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = CurrentBorder
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Phone Number (+91)",
                            color = CurrentTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        OutlinedTextField(
                            value = emergencyPhone,
                            onValueChange = { emergencyPhone = it; emergencyPhoneError = false },
                            placeholder = { Text("+91 9876543210") },
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = "Emergency Contact Phone", tint = MaterialTheme.colorScheme.primary) },
                            isError = emergencyPhoneError,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("auth_emergency_phone"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CurrentTextPrimary,
                                unfocusedTextColor = CurrentTextPrimary,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = CurrentBorder
                            )
                        )
                        if (emergencyPhoneError) {
                            Text("Emergency phone must be a valid +91 10-digit number.", color = Color.Red, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            var isAuthenticating by remember { mutableStateOf(false) }

            // Action Button
            Button(
                onClick = {
                    val trimmedEmail = email.trim()
                    val validEmail = isValidEmailAddress(trimmedEmail)
                    if (!validEmail) {
                        emailError = true
                        Toast.makeText(
                            context,
                            com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (password.length < 6) passwordError = true

                    if (isLoginMode) {
                        if (validEmail && !passwordError && !isAuthenticating) {
                            isAuthenticating = true
                            viewModel.loginUser(trimmedEmail, password) { success, errorMsg ->
                                isAuthenticating = false
                                if (success) {
                                    Toast.makeText(context, "Authentication Successful! Node connected.", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (errorMsg?.contains("email_not_confirmed", ignoreCase = true) == true ||
                                        errorMsg?.contains("Email not confirmed", ignoreCase = true) == true) {
                                        emailConfirmationTarget = trimmedEmail
                                        emailConfirmationOtp = ""
                                        showEmailConfirmationDialog = true
                                    } else if (errorMsg == com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE) {
                                        emailError = true
                                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Supabase Auth Failed: ${errorMsg ?: "Invalid Credentials"}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    } else {
                        var hasError = false
                        if (name.isBlank()) { nameError = true; hasError = true }
                        if (!validEmail) { emailError = true; hasError = true }
                        if (password.length < 6) { passwordError = true; hasError = true }

                        if (userPhone.isNotBlank() && !PhoneUtils.isValid(userPhone, userCountryCode)) {
                            userPhoneError = true
                            hasError = true
                        }

                        if (emergencyName.isBlank()) { emergencyNameError = true; hasError = true }
                        if (!PhoneUtils.isValid(emergencyPhone)) { emergencyPhoneError = true; hasError = true }

                        if (!hasError && !isAuthenticating) {
                            isAuthenticating = true
                            viewModel.registerUser(
                                name = name,
                                email = trimmedEmail,
                                password = password,
                                userPhone = userPhone.trim(),
                                userCountryCode = userCountryCode.trim().ifBlank { "+91" },
                                emergencyName = emergencyName,
                                emergencyRelation = emergencyRelation,
                                emergencyPhone = PhoneUtils.normalize(emergencyPhone)
                            ) { success, errorMsg ->
                                isAuthenticating = false
                                if (success) {
                                    Toast.makeText(context, "Registration Complete! Supabase user registered.", Toast.LENGTH_SHORT).show()
                                } else if (errorMsg == com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE) {
                                    emailError = true
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                } else if (errorMsg?.contains("already exists", ignoreCase = true) == true ||
                                    errorMsg?.contains("already registered", ignoreCase = true) == true
                                ) {
                                    isLoginMode = true
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Supabase Registration Failed: ${errorMsg ?: "Error"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                },
                enabled = !isAuthenticating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLoginMode) MaterialTheme.colorScheme.primary else EmeraldDark
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("auth_action_button")
            ) {

                if (isAuthenticating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (isLoginMode) "AUTHENTICATE SYSTEM" else "INITIALIZE ENCRYPTED ACCOUNT",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }

        if (showForgotPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showForgotPasswordDialog = false },
                containerColor = GlassySurfaceDark,
                title = {
                    Text(
                        text = "Reset Password",
                        color = CurrentTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Enter the Gmail address associated with your AIZA account. We will send a password reset link via Supabase Auth.",
                            color = CurrentTextMuted,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = forgotPasswordEmail,
                            onValueChange = {
                                forgotPasswordEmail = it
                                forgotPasswordEmailError = false
                            },
                            placeholder = { Text("user@gmail.com", color = CurrentTextMuted) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Email,
                                    contentDescription = "Reset Email",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            isError = forgotPasswordEmailError,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("forgot_password_email_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CurrentTextPrimary,
                                unfocusedTextColor = CurrentTextPrimary,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = CurrentBorder
                            )
                        )
                        if (forgotPasswordEmailError) {
                            Text(
                                text = com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE,
                                color = Color.Red,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .testTag("forgot_password_email_error")
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val target = forgotPasswordEmail.trim()
                            if (!isValidEmailAddress(target)) {
                                forgotPasswordEmailError = true
                                Toast.makeText(
                                    context,
                                    com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE,
                                    Toast.LENGTH_LONG
                                ).show()
                            } else if (!isSendingPasswordReset) {
                                isSendingPasswordReset = true
                                viewModel.sendPasswordResetEmail(target) { ok, err ->
                                    isSendingPasswordReset = false
                                    if (ok) {
                                        showForgotPasswordDialog = false
                                        Toast.makeText(
                                            context,
                                            "Password reset email sent to $target",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else if (err == com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE) {
                                        forgotPasswordEmailError = true
                                        Toast.makeText(
                                            context,
                                            err,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Reset failed: ${err ?: "Unable to send reset email"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        enabled = !isSendingPasswordReset,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("forgot_password_submit_button")
                    ) {
                        if (isSendingPasswordReset) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Send Reset Email")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showForgotPasswordDialog = false }) {
                        Text("Cancel", color = CurrentTextMuted)
                    }
                }
            )
        }

        if (showEmailConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showEmailConfirmationDialog = false },
                containerColor = GlassySurfaceDark,
                title = {
                    Text(
                        text = "Email Confirmation Required",
                        color = CurrentTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Supabase requires email confirmation for:\n$emailConfirmationTarget\n\nPlease check your inbox (including spam) for the confirmation link, or enter your 6-digit confirmation code below:",
                            color = CurrentTextMuted,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = emailConfirmationOtp,
                            onValueChange = { if (it.length <= 8) emailConfirmationOtp = it },
                            placeholder = { Text("Enter 6-digit code", color = CurrentTextMuted) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("email_otp_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CurrentTextPrimary,
                                unfocusedTextColor = CurrentTextPrimary,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = CurrentBorder
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                if (!isResendingEmail) {
                                    isResendingEmail = true
                                    viewModel.resendConfirmationEmail(emailConfirmationTarget) { resendOk, resendErr ->
                                        isResendingEmail = false
                                        if (resendOk) {
                                            Toast.makeText(context, "Confirmation email resent to $emailConfirmationTarget", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to resend: ${resendErr ?: "Error"}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            enabled = !isResendingEmail,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(
                                text = if (isResendingEmail) "Sending..." else "Resend Confirmation Email",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (emailConfirmationOtp.isNotBlank() && !isVerifyingOtp) {
                                isVerifyingOtp = true
                                viewModel.verifyEmailOtp(emailConfirmationTarget, emailConfirmationOtp) { ok, err ->
                                    isVerifyingOtp = false
                                    if (ok) {
                                        showEmailConfirmationDialog = false
                                        Toast.makeText(context, "Email verified! Successfully connected.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Verification failed: ${err ?: "Invalid OTP"}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = emailConfirmationOtp.isNotBlank() && !isVerifyingOtp,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isVerifyingOtp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Verify & Connect")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEmailConfirmationDialog = false }) {
                        Text("Cancel", color = CurrentTextMuted)
                    }
                }
            )
        }

        // ==========================================
        // 3. SET NEW PASSWORD RECOVERY DIALOG
        // ==========================================
        val isPasswordResetFlowActive by viewModel.isPasswordResetFlowActive.collectAsState()
        val passwordResetErrorMessage by viewModel.passwordResetErrorMessage.collectAsState()

        if (isPasswordResetFlowActive) {
            var newPasswordInput by remember { mutableStateOf("") }
            var confirmPasswordInput by remember { mutableStateOf("") }
            var newPasswordVisible by remember { mutableStateOf(false) }
            var isSubmittingPassword by remember { mutableStateOf(false) }
            var clientValidationError by remember { mutableStateOf<String?>(null) }

            AlertDialog(
                onDismissRequest = {
                    if (!isSubmittingPassword) {
                        viewModel.dismissPasswordResetFlow()
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LockReset,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Set New Password",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = CurrentTextPrimary
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Enter a new secure password for your AIZA account.",
                            fontSize = 12.sp,
                            color = CurrentTextMuted
                        )

                        if (passwordResetErrorMessage != null) {
                            Surface(
                                color = AlertPulseRed.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, AlertPulseRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = passwordResetErrorMessage ?: "",
                                    color = AlertPulseRed,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        if (clientValidationError != null) {
                            Text(
                                text = clientValidationError ?: "",
                                color = Color.Red,
                                fontSize = 11.sp
                            )
                        }

                        OutlinedTextField(
                            value = newPasswordInput,
                            onValueChange = {
                                newPasswordInput = it
                                clientValidationError = null
                            },
                            label = { Text("New Password (min 6 chars)") },
                            singleLine = true,
                            visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                    Icon(
                                        imageVector = if (newPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle password visibility"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reset_password_new_input")
                        )

                        OutlinedTextField(
                            value = confirmPasswordInput,
                            onValueChange = {
                                confirmPasswordInput = it
                                clientValidationError = null
                            },
                            label = { Text("Confirm New Password") },
                            singleLine = true,
                            visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reset_password_confirm_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            when {
                                newPasswordInput.length < 6 -> {
                                    clientValidationError = "Password must be at least 6 characters."
                                }
                                newPasswordInput != confirmPasswordInput -> {
                                    clientValidationError = "Passwords do not match."
                                }
                                else -> {
                                    isSubmittingPassword = true
                                    viewModel.submitNewPassword(newPasswordInput, confirmPasswordInput) { success, err ->
                                        isSubmittingPassword = false
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                "Password updated successfully! You can now sign in.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                err ?: "Failed to reset password",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isSubmittingPassword,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("reset_password_submit_button")
                    ) {
                        if (isSubmittingPassword) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Update Password")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissPasswordResetFlow() },
                        enabled = !isSubmittingPassword
                    ) {
                        Text("Cancel", color = CurrentTextMuted)
                    }
                },
                containerColor = GlassySurfaceDark,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

