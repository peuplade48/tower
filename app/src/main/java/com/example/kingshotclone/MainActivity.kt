package com.example.kingshotclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
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

// --- GÖRSEL MODELLEME VE EFEKTLER ---

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val color: Color
)

enum class EnemyType(val color: Color, val health: Int, val speed: Float, val size: Float) {
    GULYABANI(Color(0xFF43A047), 50, 3.5f, 50f),
    DEV(Color(0xFF5D4037), 200, 1.5f, 90f),
    GÖLGE(Color(0xFF212121), 30, 7.0f, 40f)
}

data class Enemy(
    var x: Float,
    var y: Float,
    val type: EnemyType,
    var currentHealth: Int,
    var angle: Float = 0f
)

data class Tower(val x: Float, val y: Float)

data class Arrow(var x: Float, var y: Float, val angle: Float)

class KingShotEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var arrows = mutableStateListOf<Arrow>()
    var particles = mutableStateListOf<Particle>()
    
    var gold by mutableIntStateOf(300)
    var health by mutableIntStateOf(100)
    var wave by mutableIntStateOf(1)
    var shakeAmount by mutableFloatStateOf(0f)

    fun update(width: Float, height: Float) {
        if (health <= 0) return
        if (shakeAmount > 0) shakeAmount -= 1f

        val currentTime = System.currentTimeMillis()
        
        // Düşman Oluşturma (Zorluk Artışı)
        if (Random.nextInt(100) < (2 + wave)) {
            val type = when {
                Random.nextFloat() < 0.1f -> EnemyType.DEV
                Random.nextFloat() < 0.2f -> EnemyType.GÖLGE
                else -> EnemyType.GULYABANI
            }
            enemies.add(Enemy(Random.nextFloat() * width, -100f, type, type.health))
        }

        // Parçacık Güncelleme
        val pIter = particles.listIterator()
        while(pIter.hasNext()){
            val p = pIter.next()
            p.x += p.vx; p.y += p.vy
            p.life -= 0.02f
            if(p.life <= 0) pIter.remove()
        }

        // Düşmanlar
        val eIter = enemies.listIterator()
        while (eIter.hasNext()) {
            val e = eIter.next()
            e.y += e.type.speed
            e.angle += 2f // Dönüş animasyonu
            
            if (e.y > height - 300f) {
                health -= 10
                shakeAmount = 15f
                createExplosion(e.x, e.y, Color.Red, 10)
                eIter.remove()
            }
        }

        // Kule Atışları
        if (currentTime % 1000 < 20) {
            towers.forEach { t ->
                enemies.minByOrNull { sqrt((it.x-t.x).pow(2) + (it.y-t.y).pow(2)) }?.let { target ->
                    val angle = atan2(target.y - t.y, target.x - t.x)
                    arrows.add(Arrow(t.x, t.y, angle))
                }
            }
        }

        // Oklar
        val aIter = arrows.listIterator()
        while (aIter.hasNext()) {
            val a = aIter.next()
            a.x += cos(a.angle) * 30f
            a.y += sin(a.angle) * 30f

            val hit = enemies.find { sqrt((it.x-a.x).pow(2) + (it.y-a.y).pow(2)) < 50f }
            if (hit != null) {
                hit.currentHealth -= 25
                createExplosion(a.x, a.y, hit.type.color, 5)
                if (hit.currentHealth <= 0) {
                    gold += 20
                    enemies.remove(hit)
                }
                aIter.remove()
            } else if (a.y < 0 || a.y > height || a.x < 0 || a.x > width) {
                aIter.remove()
            }
        }
    }

    private fun createExplosion(x: Float, y: Float, color: Color, count: Int) {
        repeat(count) {
            particles.add(Particle(x, y, Random.nextFloat()*10-5, Random.nextFloat()*10-5, 1f, color))
        }
    }

    fun buildTower(x: Float, y: Float) {
        if (gold >= 100 && y > 500f && y < 1400f) {
            towers.add(Tower(x, y))
            gold -= 100
        }
    }
}

@Composable
fun KingShotGame() {
    val engine = remember { KingShotEngine() }
    var size by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        engine.towers.add(Tower(200f, 1300f))
        engine.towers.add(Tower(800f, 1300f))
        while(isActive) {
            if (size.x > 0) engine.update(size.x, size.y)
            delay(16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Canvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { engine.buildTower(it.x, it.y) }
        }) {
            size = Offset(this.size.width, this.size.height)
            
            // Ekran sarsıntısı uygula
            val shakeX = if(engine.shakeAmount > 0) Random.nextFloat()*engine.shakeAmount else 0f
            val shakeY = if(engine.shakeAmount > 0) Random.nextFloat()*engine.shakeAmount else 0f

            translate(left = shakeX, top = shakeY) {
                // 1. ZEMİN DETAYLARI
                drawGround(this)

                // 2. KULELER (Vektörel Çizim)
                engine.towers.forEach { drawCastleTower(this, it) }

                // 3. DÜŞMANLAR (Detaylı Yaratıklar)
                engine.enemies.forEach { drawMonster(this, it) }

                // 4. OKLAR
                engine.arrows.forEach { 
                    drawCircle(Color(0xFF795548), 5f, Offset(it.x, it.y))
                    drawLine(Color.White, Offset(it.x, it.y), Offset(it.x - cos(it.angle)*20, it.y - sin(it.angle)*20), 4f)
                }

                // 5. PARÇACIKLAR
                engine.particles.forEach { 
                    drawCircle(it.color.copy(alpha = it.life), radius = 8f * it.life, center = Offset(it.x, it.y))
                }
            }
        }

        // UI
        GameUI(engine)
    }
}

private fun drawGround(scope: DrawScope) {
    // Çimen dokusu için rastgele noktalar
    scope.drawRect(Brush.verticalGradient(listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))))
}

private fun drawCastleTower(scope: DrawScope, t: Tower) {
    // Kule Gövdesi (Taş dokulu)
    scope.drawRect(Color(0xFF424242), Offset(t.x - 50f, t.y - 100f), Size(100f, 120f))
    // Üst Surlar
    for(i in 0..2) {
        scope.drawRect(Color(0xFF212121), Offset(t.x - 55f + (i*40f), t.y - 120f), Size(30f, 30f))
    }
    // Mazgal Penceresi
    scope.drawRect(Color.Black, Offset(t.x - 10f, t.y - 70f), Size(20f, 30f))
}

private fun drawMonster(scope: DrawScope, e: Enemy) {
    scope.rotate(e.angle, Offset(e.x, e.y)) {
        // Gövde
        drawCircle(e.type.color, radius = e.type.size, center = Offset(e.x, e.y))
        // Gözler
        drawCircle(Color.White, radius = e.type.size/4, center = Offset(e.x - e.type.size/3, e.y - e.type.size/4))
        drawCircle(Color.White, radius = e.type.size/4, center = Offset(e.x + e.type.size/3, e.y - e.type.size/4))
        drawCircle(Color.Red, radius = 5f, center = Offset(e.x - e.type.size/3, e.y - e.type.size/4))
        drawCircle(Color.Red, radius = 5f, center = Offset(e.x + e.type.size/3, e.y - e.type.size/4))
        // Pençeler/Boynuzlar
        val path = Path().apply {
            moveTo(e.x - e.type.size, e.y)
            lineTo(e.x - e.type.size - 20f, e.y - 40f)
            lineTo(e.x - e.type.size + 10f, e.y - 10f)
            close()
        }
        drawPath(path, e.type.color)
    }
    
    // Can Barı (Küçük ve şık)
    val barWidth = e.type.size * 2
    scope.drawRect(Color.Gray, Offset(e.x - e.type.size, e.y - e.type.size - 20f), Size(barWidth, 8f))
    scope.drawRect(Color.Red, Offset(e.x - e.type.size, e.y - e.type.size - 20f), Size(barWidth * (e.currentHealth.toFloat()/e.type.health), 8f))
}

@Composable
fun GameUI(engine: KingShotEngine) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("OBA SAVUNMASI", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Row {
            StatItem("💰 ${engine.gold}", Color(0xFFFFD600))
            Spacer(Modifier.width(16.dp))
            StatItem("🛡️ %${engine.health}", if(engine.health > 30) Color.White else Color.Red)
        }
    }
    
    if (engine.health <= 0) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) {
            Text("OBA DÜŞTÜ!", color = Color.Red, fontSize = 50.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun StatItem(label: String, color: Color) {
    Text(label, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, 
        style = TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KingShotGame() }
    }
}
