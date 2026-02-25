package com.example.privacyassistant

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Palette ──────────────────────────────────────────────────
private val BgDeep    = Color(0xFF0D0F14)
private val BgCard    = Color(0xFF161A23)
private val BgRow     = Color(0xFF1C2130)
private val BgRowAlt  = Color(0xFF181D28)
private val Accent    = Color(0xFF4F8EF7)
private val AccentSoft= Color(0x334F8EF7)
private val TextPrim  = Color(0xFFF0F2F8)
private val TextSub   = Color(0xFF7A8499)
private val Divider   = Color(0xFF252B3B)
private val ChipUnsel = Color(0xFF252B3B)

private val columns = listOf(
    ContactRelation.FRIEND        to "Friend",
    ContactRelation.MUTUAL_FRIEND to "Mutual",
    ContactRelation.FAMILY        to "Family",
    ContactRelation.UNKNOWN       to "Unknown"
)

@Composable
fun ContactClassificationScreen(
    contacts: List<ClassifiableContact>,
    selections: Map<String, ContactRelation>,
    onSelect: (String, ContactRelation) -> Unit,
    onSave: () -> Unit
) {
    val saveEnabled = selections.size == contacts.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A2540), BgDeep)
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Column {
                    Text(
                        text = "WHO ARE THESE PEOPLE?",
                        fontSize = 11.sp,
                        letterSpacing = 3.sp,
                        color = Accent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Classify your\nInstagram contacts",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrim,
                        lineHeight = 32.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${contacts.size} contacts · select one category per row",
                        fontSize = 13.sp,
                        color = TextSub
                    )
                }
            }

            // ── Column header row ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgCard)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Contact",
                    modifier = Modifier.weight(1.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSub
                )
                columns.forEach { (_, label) ->
                    Text(
                        text = label.uppercase(),
                        modifier = Modifier.weight(1f),
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSub,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Divider(color = Divider, thickness = 1.dp)

            // ── Contact rows ──────────────────────────────────
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(contacts) { index, contact ->
                    val selected = selections[contact.name]
                    val rowBg = if (index % 2 == 0) BgRow else BgRowAlt

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Contact name
                        Text(
                            text = contact.name,
                            modifier = Modifier.weight(1.8f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Radio chips per column
                        columns.forEach { (relation, _) ->
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                val isChecked = selected == relation
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (isChecked) Accent else ChipUnsel
                                        )
                                        .border(
                                            width = if (isChecked) 0.dp else 1.5.dp,
                                            color = if (isChecked) Color.Transparent else Color(0xFF3A4155),
                                            shape = RoundedCornerShape(50)
                                        )
                                        .clickable { onSelect(contact.name, relation) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isChecked) {
                                        Text(
                                            text = "✓",
                                            fontSize = 12.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Divider(color = Divider, thickness = 0.5.dp)
                }
            }

            // ── Progress + Save bar ───────────────────────────
            Surface(
                color = BgCard,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${selections.size} of ${contacts.size} classified",
                            fontSize = 12.sp,
                            color = TextSub
                        )
                        Text(
                            text = "${((selections.size.toFloat() / contacts.size) * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = Accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { selections.size.toFloat() / contacts.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Accent,
                        trackColor = ChipUnsel
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onSave,
                        enabled = saveEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            disabledContainerColor = Color(0xFF252B3B)
                        )
                    ) {
                        Text(
                            text = if (saveEnabled) "Save Classifications" else "Classify all contacts to continue",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (saveEnabled) Color.White else TextSub
                        )
                    }
                }
            }
        }
    }
}