package com.example.kingshotclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// --- VERİ MODELLERİ ---

data class Enemy(
    var x: Float,
    var y: Float,
    var health: Int,
    val speed: Float = 5f
)

data class Tower(
    val x: Float,
    val y: Float,
    val range: Float = 400f,
    val damage: Int = 10,
    var lastShotTime: Long = 0L
)

data class Projectile(
    var x: Float,
    var y: Float,
    val targetX: Float,
    val targetY: Float,
    val speed: Float = 15f
)

// --- OYUN MANTIĞI ---

class GameEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var projectiles = mutableStateListOf<Projectile>()
    var castleHealth by mutableStateOf(100)
    var gold by mutableStateOf(50)

    fun update() {
        // Düşmanları hareket ettir (Kaleye doğru, ekranın altına)
        val iterator = enemies.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            enemy.y += enemy.speed
            
            // Kale hasarı
            if (enemy.y > 2000f) { // Ekran altı varsayımı
                castleHealth -= 5
                enemies.remove(enemy)
                break
            }
        }

        // Kulelerin ateş etmesi
        towers.forEach { tower ->
            val currentTime = System.currentTimeMillis()
            if (currentTime - tower.lastShotTime > 1000) { // Saniyede 1 atış
                val target = enemies.firstOrNull { 
                    dist(it.x, it.y, tower.x, tower.y) < tower.range 
                }
                if (target != null) {
                    projectiles.add(Projectile(tower.x, tower.y, target.x, target.y))
                    tower.lastShotTime = currentTime
                }
            }
        }

        // Mermileri hareket ettir
        val pIterator = projectiles.iterator()
        while (pIterator.hasNext()) {
            val p = pIterator.next()
            val dx = p.targetX - p.x
            val dy = p.targetY - p.y
            val angle = atan2(dy, dx)
            p.x += cos(angle) * p.speed
            p.y += sin(angle) * p.speed

            // Hedefe ulaştı mı? (Basit çarpışma)
            val hitEnemy = enemies.firstOrNull { dist(it.x, it.y, p.x, p.y) < 30f }
            if (hitEnemy != null) {
                hitEnemy.health -= 10
                if (hitEnemy.health <= 0) {
                    enemies.remove(hitEnemy)
                    gold += 10
                }
                projectiles.remove(p)
                break
            }
        }
        
        // Rastgele düşman spawn
        if (Math.random() < 0.02) {
            enemies.add(Enemy((200..800).random().toFloat(), 0f, 20))
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }
}

// --- UI BİLEŞENLERİ ---

@Composable
fun GameScreen() {
    val engine = remember { GameEngine() }
    
    // Oyun Döngüsü
    LaunchedEffect(Unit) {
        // İlk kuleyi koyalım
        engine.towers.add(Tower(500f, 1500f))
        
        while (true) {
            engine.update()
            delay(16) // ~60 FPS
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Arkaplan
        drawRect(Color(0xFF2D5A27))

        // Kale (Alt bölge)
        drawRect(Color.Gray, topLeft = Offset(0f, 1800f), size = androidx.compose.ui.geometry.Size(size.width, 200f))

        // Düşmanlar
        engine.enemies.forEach { enemy ->
            drawCircle(Color.Red, radius = 25f, center = Offset(enemy.x, enemy.y))
        }

        // Kuleler
        engine.towers.forEach { tower ->
            drawRect(Color.Blue, topLeft = Offset(tower.x - 40f, tower.y - 40f), size = androidx.compose.ui.geometry.Size(80f, 80f))
        }

        // Mermiler
        engine.projectiles.forEach { p ->
            drawCircle(Color.Yellow, radius = 10f, center = Offset(p.x, p.y))
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GameScreen()
        }
    }
}
