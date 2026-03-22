package com.example.kingshotclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

/**
 * KingShot: Arpagu'nun Savunması
 * Türk mitolojisi temalı Tower Defense oyunu.
 */

// --- HİKAYE VE DÜŞMAN TİPLERİ ---

enum class EnemyType(val color: Color, val health: Int, val speed: Float, val reward: Int) {
    GULYABANI(Color(0xFF4E342E), 30, 4f, 15),       // Standart kahverengi düşman
    KARAKONCOLOS(Color(0xFF1B5E20), 80, 2.5f, 40),  // Yeşil, dayanıklı tank
    AL_KARISI(Color(0xFFB71C1C), 25, 7.5f, 25)      // Kırmızı, çok hızlı düşman
}

data class Enemy(
    var x: Float,
    var y: Float,
    val type: EnemyType,
    var currentHealth: Int
)

data class Tower(
    val x: Float,
    val y: Float,
    val color: Color = Color(0xFF8D6E63) // Ahşap kule rengi
)

data class Arrow(
    var x: Float,
    var y: Float,
    val targetX: Float,
    val targetY: Float
)

// --- OYUN MOTORU ---

class KingShotEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var arrows = mutableStateListOf<Arrow>()
    
    var gold by mutableIntStateOf(100)
    var health by mutableIntStateOf(100)
    var score by mutableIntStateOf(0)
    var wave by mutableIntStateOf(1)
    
    private var lastSpawnTime = 0L
    private var lastShotTime = 0L

    fun update(width: Float, height: Float) {
        if (health <= 0) return

        val currentTime = System.currentTimeMillis()
        val spawnRate = max(400, 2200 - (wave * 120)).toLong()
        
        // 1. Düşman Üretimi
        if (currentTime - lastSpawnTime > spawnRate) {
            val type = when {
                wave > 5 && Math.random() < 0.25 -> EnemyType.KARAKONCOLOS
                wave > 3 && Math.random() < 0.35 -> EnemyType.AL_KARISI
                else -> EnemyType.GULYABANI
            }
            enemies.add(Enemy((100f..(width - 100f)).random(), -50f, type, type.health))
            lastSpawnTime = currentTime
        }

        // 2. Düşman Hareketleri ve Kale Hasarı
        val enemyIterator = enemies.iterator()
        while (enemyIterator.hasNext()) {
            val enemy = enemyIterator.next()
            enemy.y += enemy.type.speed
            
            if (enemy.y > height - 250f) {
                health -= 10
                enemies.remove(enemy)
                break
            }
        }

        // 3. Kulelerin Ateş Etmesi
        if (currentTime - lastShotTime > 750) {
            towers.forEach { tower ->
                val target = enemies.minByOrNull { dist(it.x, it.y, tower.x, tower.y) }
                if (target != null && dist(target.x, target.y, tower.x, tower.y) < 600f) {
                    arrows.add(Arrow(tower.x, tower.y, target.x, target.y))
                }
            }
            lastShotTime = currentTime
        }

        // 4. Ok Hareketleri ve Vuruş Kontrolü
        val arrowIterator = arrows.iterator()
        while (arrowIterator.hasNext()) {
            val arrow = arrowIterator.next()
            val dx = arrow.targetX - arrow.x
            val dy = arrow.targetY - arrow.y
            val angle = atan2(dy, dx)
            
            arrow.x += cos(angle) * 30f
            arrow.y += sin(angle) * 30f

            val hitEnemy = enemies.find { dist(it.x, it.y, arrow.x, arrow.y) < 45f }
            if (hitEnemy != null) {
                hitEnemy.currentHealth -= 15
                if (hitEnemy.currentHealth <= 0) {
                    gold += hitEnemy.type.reward
                    score += hitEnemy.type.reward * 2
                    enemies.remove(hitEnemy)
                    if (score > 0 && score % 500 == 0) wave++
                }
                arrows.remove(arrow)
                break
            }
            
            if (arrow.y < -100 || arrow.x < -100 || arrow.x > width + 100) arrows.remove(arrow)
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) = sqrt((x2-x1).pow(2) + (y2-y1).pow(2))
}

// --- UI BİLEŞENLERİ ---

@Composable
fun KingShotGame() {
    val engine = remember { KingShotEngine() }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        // İlk Savunma Kuleleri
        engine.towers.add(Tower(250f, 1550f))
        engine.towers.add(Tower(850f, 1550f))
        
        while(true) {
            if (canvasSize.x > 0) engine.update(canvasSize.x, canvasSize.y)
            delay(16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF2E7D32))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = Offset(size.width, size.height)
            
            // Toprak Yol
            drawRect(Color(0xFF795548), size = size)

            // Oba Sınırı
            drawRect(Color(0xFF3E2723), 
                topLeft = Offset(0f, size.height - 280f), 
                size = androidx.compose.ui.geometry.Size(size.width, 280f))

            // Düşmanlar
            engine.enemies.forEach { enemy ->
                drawCircle(enemy.type.color, radius = 35f, center = Offset(enemy.x, enemy.y))
                // Can Barı
                drawRect(Color.Red, Offset(enemy.x - 35f, enemy.y - 60f), size = androidx.compose.ui.geometry.Size(70f, 8f))
                drawRect(Color.Green, Offset(enemy.x - 35f, enemy.y - 60f), 
                    size = androidx.compose.ui.geometry.Size(70f * (enemy.currentHealth.toFloat() / enemy.type.health), 8f))
            }

            // Kuleler / Çadırlar
            engine.towers.forEach { tower ->
                drawRect(tower.color, Offset(tower.x - 60f, tower.y - 120f), size = androidx.compose.ui.geometry.Size(120f, 140f))
                drawCircle(Color(0xFF4E342E), radius = 50f, center = Offset(tower.x, tower.y - 130f))
            }

            // Oklar
            engine.arrows.forEach { arrow ->
                drawLine(Color(0xFFFFEB3B), start = Offset(arrow.x, arrow.y), end = Offset(arrow.x - 15f, arrow.y - 15f), strokeWidth = 6f)
            }
        }

        // Oyun Bilgileri
        Column(modifier = Modifier.padding(24.dp).align(Alignment.TopStart)) {
            StatusText("ALTIN: ${engine.gold}", Color(0xFFFFD700))
            StatusText("OBA SAĞLIĞI: %${engine.health}", if(engine.health > 30) Color.White else Color.Red)
            StatusText("DALGA: ${engine.wave}", Color.Cyan)
        }
        
        if(engine.health <= 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Text("OBA DÜŞTÜ!\nSkor: ${engine.score}\nDalga: ${engine.wave}", 
                    color = Color.Red, fontSize = 42.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
fun StatusText(text: String, color: Color) {
    Text(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            shadow = Shadow(Color.Black, blurRadius = 10f)
        ),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KingShotGame() }
    }
}
