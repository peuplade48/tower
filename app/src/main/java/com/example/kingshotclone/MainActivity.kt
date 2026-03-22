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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

// --- HİKAYE VE DÜŞMAN TİPLERİ ---

enum class EnemyType(val color: Color, val health: Int, val speed: Float, val reward: Int) {
    GULYABANI(Color(0xFF4E342E), 30, 4f, 15),       // Standart düşman
    KARAKONCOLOS(Color(0xFF1B5E20), 80, 2.5f, 40),  // Tank düşman
    AL_KARISI(Color(0xFFB71C1C), 25, 7.5f, 25)      // Hızlı düşman
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
    val color: Color = Color(0xFF8D6E63)
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
        val spawnRate = max(400L, 2200L - (wave * 120L))
        
        if (currentTime - lastSpawnTime > spawnRate) {
            val type = when {
                wave > 5 && Math.random() < 0.25 -> EnemyType.KARAKONCOLOS
                wave > 3 && Math.random() < 0.35 -> EnemyType.AL_KARISI
                else -> EnemyType.GULYABANI
            }
            enemies.add(Enemy((100f..(width - 100f)).random(), -50f, type, type.health))
            lastSpawnTime = currentTime
        }

        val enemyIterator = enemies.iterator()
        while (enemyIterator.hasNext()) {
            val enemy = enemyIterator.next()
            enemy.y += enemy.type.speed
            
            if (enemy.y > height - 250f) {
                health -= 10
                enemyIterator.remove()
            }
        }

        if (currentTime - lastShotTime > 750) {
            towers.forEach { tower ->
                val target = enemies.minByOrNull { dist(it.x, it.y, tower.x, tower.y) }
                if (target != null && dist(target.x, target.y, tower.x, tower.y) < 600f) {
                    arrows.add(Arrow(tower.x, tower.y, target.x, target.y))
                }
            }
            lastShotTime = currentTime
        }

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
                arrowIterator.remove()
                continue
            }
            
            if (arrow.y < -100 || arrow.x < -100 || arrow.x > width + 100 || arrow.y > height + 100) {
                arrowIterator.remove()
            }
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) = sqrt((x2-x1).pow(2) + (y2-y1).pow(2))
}

@Composable
fun KingShotGame() {
    val engine = remember { KingShotEngine() }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
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
            
            // Arka Plan
            drawRect(color = Color(0xFF795548), size = size)

            // Savunma Hattı - Hata Düzeltildi: Size() constructor kullanıldı
            drawRect(
                color = Color(0xFF3E2723), 
                topLeft = Offset(0f, size.height - 280f), 
                size = Size(size.width, 280f)
            )

            // Düşmanlar
            engine.enemies.forEach { enemy ->
                drawCircle(enemy.type.color, radius = 35f, center = Offset(enemy.x, enemy.y))
                drawRect(
                    color = Color.Red, 
                    topLeft = Offset(enemy.x - 35f, enemy.y - 60f), 
                    size = Size(70f, 8f)
                )
                drawRect(
                    color = Color.Green, 
                    topLeft = Offset(enemy.x - 35f, enemy.y - 60f), 
                    size = Size(70f * (enemy.currentHealth.toFloat() / enemy.type.health), 8f)
                )
            }

            // Kuleler
            engine.towers.forEach { tower ->
                drawRect(
                    color = tower.color, 
                    topLeft = Offset(tower.x - 60f, tower.y - 120f), 
                    size = Size(120f, 140f)
                )
                drawCircle(Color(0xFF4E342E), radius = 50f, center = Offset(tower.x, tower.y - 130f))
            }

            // Oklar
            engine.arrows.forEach { arrow ->
                drawLine(
                    color = Color(0xFFFFEB3B), 
                    start = Offset(arrow.x, arrow.y), 
                    end = Offset(arrow.x - 15f, arrow.y - 15f), 
                    strokeWidth = 6f
                )
            }
        }

        Column(modifier = Modifier.padding(24.dp).align(Alignment.TopStart)) {
            GameInfoText("ALTIN: ${engine.gold}", Color(0xFFFFD700))
            GameInfoText("OBA SAĞLIĞI: %${engine.health}", if(engine.health > 30) Color.White else Color.Red)
            GameInfoText("DALGA: ${engine.wave}", Color.Cyan)
        }
        
        if(engine.health <= 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Text(
                    text = "OBA DÜŞTÜ!\nSkor: ${engine.score}\nDalga: ${engine.wave}", 
                    color = Color.Red, 
                    fontSize = 42.sp, 
                    fontWeight = FontWeight.Bold, 
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun GameInfoText(text: String, color: Color) {
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
