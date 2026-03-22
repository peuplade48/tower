package com.example.kingshotclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*
import kotlin.random.Random

// --- OYUN AYARLARI VE GÖRSEL VARLIKLAR (EMOJİLER) ---

enum class EnemyType(val emoji: String, val health: Int, val speed: Float, val reward: Int) {
    GULYABANI("👹", 40, 3.8f, 15),
    KARAKONCOLOS("🐗", 120, 2.0f, 50),
    AL_KARISI("🧛", 30, 8.0f, 30)
}

data class Enemy(
    var x: Float,
    var y: Float,
    val type: EnemyType,
    var currentHealth: Int,
    val id: Long = Random.nextLong()
)

data class Tower(
    val x: Float,
    val y: Float,
    val level: Int = 1
)

data class Arrow(
    var x: Float,
    var y: Float,
    val targetX: Float,
    val targetY: Float,
    val angle: Float
)

// --- OYUN MOTORU ---

class KingShotEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var arrows = mutableStateListOf<Arrow>()
    
    var gold by mutableIntStateOf(250)
    var health by mutableIntStateOf(100)
    var score by mutableIntStateOf(0)
    var wave by mutableIntStateOf(1)
    
    private var lastSpawnTime = 0L
    private var lastShotTime = 0L

    fun update(width: Float, height: Float) {
        if (health <= 0) return

        val currentTime = System.currentTimeMillis()
        val spawnRate = max(400, 2500 - (wave * 150)).toLong()
        
        // Düşman Oluşturma
        if (currentTime - lastSpawnTime > spawnRate) {
            val type = when {
                wave > 5 && Random.nextFloat() < 0.3f -> EnemyType.KARAKONCOLOS
                wave > 2 && Random.nextFloat() < 0.4f -> EnemyType.AL_KARISI
                else -> EnemyType.GULYABANI
            }
            val spawnX = 100f + Random.nextFloat() * (width - 200f)
            enemies.add(Enemy(spawnX, -100f, type, type.health))
            lastSpawnTime = currentTime
        }

        // Düşman Hareketleri
        val enemyIterator = enemies.listIterator()
        while (enemyIterator.hasNext()) {
            val enemy = enemyIterator.next()
            enemy.y += enemy.type.speed
            
            // Oba Savunma Hattına Ulaştı mı?
            if (enemy.y > height - 350f) {
                health = max(0, health - 10)
                enemyIterator.remove()
            }
        }

        // Kule Atış Mantığı
        if (currentTime - lastShotTime > 800) {
            towers.forEach { tower ->
                val target = enemies.minByOrNull { dist(it.x, it.y, tower.x, tower.y) }
                if (target != null && dist(target.x, target.y, tower.x, tower.y) < 800f) {
                    val dx = target.x - tower.x
                    val dy = target.y - tower.y
                    arrows.add(Arrow(tower.x, tower.y, target.x, target.y, atan2(dy, dx)))
                }
            }
            lastShotTime = currentTime
        }

        // Ok Hareketleri ve Çarpışma
        val arrowIterator = arrows.listIterator()
        while (arrowIterator.hasNext()) {
            val arrow = arrowIterator.next()
            arrow.x += cos(arrow.angle) * 35f
            arrow.y += sin(arrow.angle) * 35f

            val hitEnemy = enemies.find { dist(it.x, it.y, arrow.x, arrow.y) < 60f }
            if (hitEnemy != null) {
                hitEnemy.currentHealth -= 20
                if (hitEnemy.currentHealth <= 0) {
                    gold += hitEnemy.type.reward
                    score += hitEnemy.type.reward * 5
                    enemies.remove(hitEnemy)
                    if (score > 0 && score % 1000 == 0) wave++
                }
                arrowIterator.remove()
                continue
            }
            
            if (arrow.y < -100 || arrow.y > height + 100 || arrow.x < -100 || arrow.x > width + 100) {
                arrowIterator.remove()
            }
        }
    }

    fun buildTower(x: Float, y: Float) {
        val cost = 75
        if (gold >= cost && y < 1400f && y > 400f) {
            if (towers.none { dist(it.x, it.y, x, y) < 180f }) {
                towers.add(Tower(x, y))
                gold -= cost
            }
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) = sqrt((x2-x1).pow(2) + (y2-y1).pow(2))
}

// --- GÖRSEL BİLEŞEN ---

@Composable
fun KingShotGame() {
    val engine = remember { KingShotEngine() }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        // Başlangıç kuleleri
        engine.towers.add(Tower(300f, 1350f))
        engine.towers.add(Tower(800f, 1350f))
        
        while(isActive) {
            if (canvasSize.x > 0) engine.update(canvasSize.x, canvasSize.y)
            delay(16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF2E7D32))) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .clickable { offset -> engine.buildTower(offset.x, offset.y) }
        ) {
            canvasSize = Offset(size.width, size.height)
            
            // 1. ZEMİN VE YOL (Bozkır havası)
            drawRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF388E3C), Color(0xFF2E7D32))),
                size = size
            )
            
            // Patika yol
            drawRect(
                color = Color(0xFF8D6E63).copy(alpha = 0.3f),
                topLeft = Offset(size.width * 0.2f, 0f),
                size = Size(size.width * 0.6f, size.height)
            )

            // 2. OBA / SAVUNMA HATTI (Alt Kısım)
            drawRect(
                color = Color(0xFF4E342E),
                topLeft = Offset(0f, size.height - 350f),
                size = Size(size.width, 350f)
            )
            // Çadır emojileri (Oba görünümü)
            for (i in 0..5) {
                drawText(textMeasurer, "⛺", Offset(i * (size.width/5) - 40f, size.height - 250f), style = TextStyle(fontSize = 50.sp))
            }

            // 3. KULELER
            engine.towers.forEach { tower ->
                // Kule tabanı
                drawRect(Color(0xFF5D4037), Offset(tower.x - 60f, tower.y - 80f), Size(120f, 100f))
                // Gözcü/Okçu
                drawText(textMeasurer, "🏹", Offset(tower.x - 45f, tower.y - 150f), style = TextStyle(fontSize = 45.sp))
                // Kule bayrağı
                drawRect(Color.Red, Offset(tower.x + 40f, tower.y - 140f), Size(30f, 20f))
            }

            // 4. DÜŞMANLAR
            engine.enemies.forEach { enemy ->
                // Gölge
                drawCircle(Color.Black.copy(alpha = 0.2f), radius = 30f, center = Offset(enemy.x, enemy.y + 20f))
                
                // Canavar Emojisi
                drawText(textMeasurer, enemy.type.emoji, Offset(enemy.x - 50f, enemy.y - 60f), style = TextStyle(fontSize = 50.sp))
                
                // Can Barı
                val healthWidth = 100f
                drawRect(Color.Black, Offset(enemy.x - 50f, enemy.y - 90f), Size(healthWidth, 10f))
                drawRect(
                    if(enemy.currentHealth > enemy.type.health/2) Color.Green else Color.Red,
                    Offset(enemy.x - 50f, enemy.y - 90f),
                    Size(healthWidth * (enemy.currentHealth.toFloat() / enemy.type.health), 10f)
                )
            }

            // 5. OKLAR
            engine.arrows.forEach { arrow ->
                drawCircle(Color(0xFFFFD700), radius = 6f, center = Offset(arrow.x, arrow.y))
                // İz efekti
                drawLine(Color(0xFFEEEEEE), Offset(arrow.x, arrow.y), Offset(arrow.x - cos(arrow.angle)*30, arrow.y - sin(arrow.angle)*30), strokeWidth = 3f)
            }
        }

        // ARAYÜZ (UI)
        Column(modifier = Modifier.padding(20.dp).align(Alignment.TopStart)) {
            StatusCard("💰 Altın: ${engine.gold}", Color(0xFFFFD700))
            StatusCard("❤️ Oba: %${engine.health}", if(engine.health > 30) Color.White else Color.Red)
            StatusCard("🌪️ Dalga: ${engine.wave}", Color.Cyan)
            
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Yeni Kule: 75 Altın (Ekrana Tıkla)", 
                color = Color.White.copy(alpha = 0.8f), 
                fontSize = 14.sp, 
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 5f))
            )
        }
        
        // OYUN BİTTİ EKRANI
        if(engine.health <= 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("OBA YAĞMALANDI!", color = Color.Red, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text("Puan: ${engine.score}\nUlaşılan Dalga: ${engine.wave}", color = Color.White, fontSize = 24.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun StatusCard(text: String, color: Color) {
    Text(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            shadow = Shadow(Color.Black, offset = Offset(2f, 2f), blurRadius = 8f)
        ),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KingShotGame() }
    }
}
