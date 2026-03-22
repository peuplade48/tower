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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

/**
 * KingShot: Arpagu'nun Savunması - Gelişmiş Sürüm
 */

enum class EnemyType(val color: Color, val health: Int, val speed: Float, val reward: Int) {
    GULYABANI(Color(0xFF4E342E), 35, 4.2f, 15),
    KARAKONCOLOS(Color(0xFF1B5E20), 100, 2.3f, 50),
    AL_KARISI(Color(0xFFB71C1C), 25, 8.5f, 30)
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

class KingShotEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var arrows = mutableStateListOf<Arrow>()
    
    var gold by mutableIntStateOf(200)
    var health by mutableIntStateOf(100)
    var score by mutableIntStateOf(0)
    var wave by mutableIntStateOf(1)
    
    private var lastSpawnTime = 0L
    private var lastShotTime = 0L

    fun update(width: Float, height: Float) {
        if (health <= 0) return

        val currentTime = System.currentTimeMillis()
        val spawnRate = max(350, 2300 - (wave * 150)).toLong()
        
        if (currentTime - lastSpawnTime > spawnRate) {
            val type = when {
                wave > 6 && Math.random() < 0.3 -> EnemyType.KARAKONCOLOS
                wave > 3 && Math.random() < 0.4 -> EnemyType.AL_KARISI
                else -> EnemyType.GULYABANI
            }
            val spawnX = (width * 0.25f + (Math.random().toFloat() * width * 0.5f))
            enemies.add(Enemy(spawnX, -50f, type, type.health))
            lastSpawnTime = currentTime
        }

        val enemyIterator = enemies.iterator()
        while (enemyIterator.hasNext()) {
            val enemy = enemyIterator.next()
            enemy.y += enemy.type.speed
            if (enemy.y > height - 280f) {
                health -= 15
                enemies.remove(enemy)
                break
            }
        }

        if (currentTime - lastShotTime > 700) {
            towers.forEach { tower ->
                val target = enemies.minByOrNull { dist(it.x, it.y, tower.x, tower.y) }
                if (target != null && dist(target.x, target.y, tower.x, tower.y) < 750f) {
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
            
            arrow.x += cos(angle) * 40f
            arrow.y += sin(angle) * 40f

            val hitEnemy = enemies.find { dist(it.x, it.y, arrow.x, arrow.y) < 55f }
            if (hitEnemy != null) {
                hitEnemy.currentHealth -= 25
                if (hitEnemy.currentHealth <= 0) {
                    gold += hitEnemy.type.reward
                    score += hitEnemy.type.reward * 3
                    enemies.remove(hitEnemy)
                    if (score > 0 && score % 1200 == 0) wave++
                }
                arrows.remove(arrow)
                break
            }
            if (arrow.y < -100 || arrow.x < -100 || arrow.x > width + 100 || arrow.y > height + 100) arrows.remove(arrow)
        }
    }

    fun buildTower(x: Float, y: Float) {
        val cost = 60
        if (gold >= cost && y < 1400f) {
            if (towers.none { dist(it.x, it.y, x, y) < 170f }) {
                towers.add(Tower(x, y))
                gold -= cost
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
        engine.towers.add(Tower(200f, 1300f))
        engine.towers.add(Tower(size.run { 900f }, 1300f))
        while(true) {
            if (canvasSize.x > 0) engine.update(canvasSize.x, canvasSize.y)
            delay(16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Canvas(modifier = Modifier.fillMaxSize().clickable { offset -> engine.buildTower(offset.x, offset.y) }) {
            canvasSize = Offset(size.width, size.height)
            drawRect(Color(0xFF5D4037), topLeft = Offset(size.width * 0.25f, 0f), size = androidx.compose.ui.geometry.Size(size.width * 0.5f, size.height))
            drawRect(Color(0xFF3E2723), topLeft = Offset(0f, size.height - 280f), size = androidx.compose.ui.geometry.Size(size.width, 280f))

            engine.enemies.forEach { enemy ->
                drawCircle(enemy.type.color, radius = 40f, center = Offset(enemy.x, enemy.y))
                drawRect(Color.Red, Offset(enemy.x - 40f, enemy.y - 70f), size = androidx.compose.ui.geometry.Size(80f, 12f))
                drawRect(Color.Green, Offset(enemy.x - 40f, enemy.y - 70f), size = androidx.compose.ui.geometry.Size(80f * (enemy.currentHealth.toFloat() / enemy.type.health), 12f))
            }

            engine.towers.forEach { tower ->
                drawRect(tower.color, Offset(tower.x - 70f, tower.y - 90f), size = androidx.compose.ui.geometry.Size(140f, 120f))
                drawCircle(Color(0xFF4E342E), radius = 65f, center = Offset(tower.x, tower.y - 95f))
                drawCircle(Color(0xFFD4AF37), radius = 15f, center = Offset(tower.x, tower.y - 160f))
            }

            engine.arrows.forEach { arrow ->
                drawLine(Color(0xFFFFEB3B), start = Offset(arrow.x, arrow.y), end = Offset(arrow.x - 18f, arrow.y - 18f), strokeWidth = 9f)
            }
        }

        Column(modifier = Modifier.padding(24.dp).align(Alignment.TopStart)) {
            StatusText("ALTIN: ${engine.gold}", Color(0xFFFFD700))
            StatusText("SAĞLIK: %${engine.health}", if(engine.health > 30) Color.White else Color.Red)
            StatusText("DALGA: ${engine.wave}", Color.Cyan)
            Text("İnşa: 60 Altın | Ekrana Tıkla!", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        
        if(engine.health <= 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("OBA DÜŞTÜ!", color = Color.Red, fontSize = 50.sp, fontWeight = FontWeight.Bold)
                    Text("Skor: ${engine.score}\nDalga: ${engine.wave}", color = Color.White, fontSize = 28.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun StatusText(text: String, color: Color) {
    Text(text = text, style = TextStyle(color = color, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, shadow = Shadow(Color.Black, blurRadius = 14f)), modifier = Modifier.padding(vertical = 4.dp))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KingShotGame() }
    }
}
