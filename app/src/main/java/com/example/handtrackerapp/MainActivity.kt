package com.example.handtrackerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        setContent {
            ControlSetupScreen()
        }
    }
}

@Composable
fun ControlSetupScreen() {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val isServiceRunning by HandControlAccessibilityService.isServiceRunning.collectAsState()
    val isTrackingEnabled by HandControlAccessibilityService.isTrackingEnabled.collectAsState()
    val settings by SettingsManager.settings.collectAsState()

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("Air Hand Control", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))

        // การ์ดสวิตช์หลักเปิด/ปิดโหมดควบคุม
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isTrackingEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isTrackingEnabled) "โหมดควบคุม: เปิดทำงาน" else "โหมดควบคุม: ปิดอยู่",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ชู 2 นิ้ว (✌️) = Back | กำมือ (✊) = Tab Bar",
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = isTrackingEnabled,
                    enabled = isServiceRunning && hasCamera,
                    onCheckedChange = { HandControlAccessibilityService.setTrackingEnabled(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // การ์ดปรับแต่ง Sensitivity & UI
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ปรับแต่งความไวและการแสดงผล", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(12.dp))

                Text("ความเร็ว Pointer: ${"%.1f".format(settings.cursorSpeed)}x")
                Slider(
                    value = settings.cursorSpeed,
                    onValueChange = { SettingsManager.update(context, settings.copy(cursorSpeed = it)) },
                    valueRange = 1.2f..5.0f
                )

                Text("ขนาดจุด Pointer: ${settings.pointerRadius.toInt()} px")
                Slider(
                    value = settings.pointerRadius,
                    onValueChange = { SettingsManager.update(context, settings.copy(pointerRadius = it)) },
                    valueRange = 12f..40f
                )

                Text("ความไวการจีบนิ้วคลิก: ${"%.2f".format(settings.pinchThreshold)}")
                Slider(
                    value = settings.pinchThreshold,
                    onValueChange = { SettingsManager.update(context, settings.copy(pinchThreshold = it)) },
                    valueRange = 0.18f..0.35f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("สั่นเตือนเมื่อสั่งงาน (Haptic)")
                    Switch(
                        checked = settings.hapticEnabled,
                        onCheckedChange = { SettingsManager.update(context, settings.copy(hapticEnabled = it)) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("ตั้งค่าสิทธิ์พื้นฐาน", fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasCamera) "1. สิทธิ์กล้อง (อนุญาตแล้ว ✓)" else "1. อนุญาตสิทธิ์กล้อง")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2. เปิดสิทธิ์แสดงทับแอปอื่น (Overlay)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isServiceRunning) "3. Accessibility Service (เปิดอยู่ ✓)" else "3. เปิดใช้งาน Accessibility Service")
        }
    }
}