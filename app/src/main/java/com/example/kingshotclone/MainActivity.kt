package com.example.kingshotclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*
import kotlin.random.Random

// --- MODELLER ---

data class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float, val color: Color,
    val size: Float = 5f
)

enum class EnemyType(val color: Color, val health: Int, val speed: Float, val size: Float, val isBoss: Boolean = false) {
    PIYADE(Color(0xFF43A047), 60, 3.0f, 45f),
    KALKANLI(Color(0xFF757575), 150, 2.0f, 55f),
    HIZLI_GÖLGE(Color(0xFF6A1B9A), 40, 6.0f, 35f),
    DEV_YARATIK(Color(0xFF3E2723), 1000, 1.0f, 110f, true)
}

data class Enemy(
    var x: Float, var y: Float,
    val type: EnemyType,
    var currentHealth: Int,
    var slowDuration: Int = 0,
    var id: Long = Random.nextLong()
)

enum class TowerType(val color: Color, val cost: Int, val range: Float, val damage: Int, val isIce: Boolean = false) {
    OKCU(Color(0xFF8D6E63), 100, 700f, 25),
    BUZ(Color(0xFF4FC3F7), 200, 500f, 10, true)
}

data class Tower(val x: Float, val y: Float, val type: TowerType)

data class Projectile(var x: Float, var y: Float, val angle: Float, val isIce: Boolean)

// --- MOTOR ---

class KingShotEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var projectiles = mutableStateListOf<Projectile>()
    var particles = mutableStateListOf<Particle>()
    var clouds = mutableStateListOf<Offset>()
    
    var gold by mutableIntStateOf(400)
    var health by mutableIntStateOf(100)
    var wave by mutableIntStateOf(1)
    var shakeAmount by mutableFloatStateOf(0f)
    var selectedTowerType by mutableStateOf(TowerType.OKCU)

    init {
        repeat(5) { clouds.add(Offset(Random.nextFloat() * 1000, Random.nextFloat() * 2000)) }
    }

    fun update(width: Float, height: Float) {
        if (health <= 0) return
        if (shakeAmount > 0) shakeAmount *= 0.9f

        // Bulut Hareketi
        clouds.forEachIndexed { i, c ->
            var newX = c.x + 0.5f
            if (newX > width + 200) newX = -200f
            clouds[i] = Offset(newX, c.y)
        }

        // Düşman Spawn
        if (Random.nextInt(200) < (3 + wave)) {
            val type = when {
                wave >= 5 && Random.nextFloat() < 0.05f -> EnemyType.DEV_YARATIK
                Random.nextFloat() < 0.2f -> EnemyType.KALKANLI
                Random.nextFloat() < 0.15f -> EnemyType.HIZLI_GÖLGE
                else -> EnemyType.PIYADE
            }
            enemies.add(Enemy(Random.nextFloat() * width, -100f, type, type.health))
        }

        // Parçacıklar
        val pIter = particles.listIterator()
        while(pIter.hasNext()){
            val p = pIter.next()
            p.x += p.vx; p.y += p.vy
            p.life -= 0.015f
            if(p.life <= 0) pIter.remove()
        }

        // Düşman Güncelleme
        val eIter = enemies.listIterator()
        while (eIter.hasNext()) {
            val e = eIter.next()
            val currentSpeed = if(e.slowDuration > 0) e.type.speed * 0.4f else e.type.speed
            e.y += currentSpeed
            if(e.slowDuration > 0) e.slowDuration--
            
            if (e.y > height - 320f) {
                health -= if(e.type.isBoss) 50 else 10
                shakeAmount = 25f
                createExplosion(e.x, e.y, Color.Red, 20)
                eIter.remove()
            }
        }

        // Atış Mantığı
        if (System.currentTimeMillis() % 1000 < 50) {
            towers.forEach { t ->
                val target = enemies.minByOrNull { sqrt((it.x-t.x).pow(2) + (it.y-t.y).pow(2)) }
                if (target != null && sqrt((target.x-t.x).pow(2) + (target.y-t.y).pow(2)) < t.type.range) {
                    projectiles.add(Projectile(t.x, t.y, atan2(target.y - t.y, target.x - t.x), t.type.isIce))
                }
            }
        }

        // Mermiler
        val projIter = projectiles.listIterator()
        while (projIter.hasNext()) {
            val p = projIter.next()
            p.x += cos(p.angle) * 35f
            p.y += sin(p.angle) * 35f

            val hit = enemies.find { sqrt((it.x-p.x).pow(2) + (it.y-p.y).pow(2)) < 60f }
            if (hit != null) {
                hit.currentHealth -= if(p.isIce) 10 else 30
                if(p.isIce) hit.slowDuration = 100
                createExplosion(p.x, p.y, if(p.isIce) Color.Cyan else hit.type.color, 8)
                if (hit.currentHealth <= 0) {
                    gold += if(hit.type.isBoss) 200 else 25
                    if(gold > 2000 && wave < 10) wave++
                    enemies.remove(hit)
                }
                projIter.remove()
            } else if (p.y < -50 || p.y > height + 50) projIter.remove()
        }
    }

    private fun createExplosion(x: Float, y: Float, color: Color, count: Int) {
        repeat(count) {
            particles.add(Particle(x, y, Random.nextFloat()*12-6, Random.nextFloat()*12-6, 1f, color))
        }
    }

    fun build(x: Float, y: Float) {
        if (gold >= selectedTowerType.cost && y > 400f && y < 1400f) {
            towers.add(Tower(x, y, selectedTowerType))
            gold -= selectedTowerType.cost
        }
    }
}

// --- GÖRSEL ÇİZİMLER ---

@Composable
fun KingShotGame() {
    val engine = remember { KingShotEngine() }
    var size by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        while(isActive) {
            if (size.x > 0) engine.update(size.x, size.y)
            delay(16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Canvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { engine.build(it.x, it.y) }
        }) {
            size = Offset(this.size.width, this.size.height)
            
            val shakeX = (Random.nextFloat() - 0.5f) * engine.shakeAmount
            val shakeY = (Random.nextFloat() - 0.5f) * engine.shakeAmount

            translate(left = shakeX, top = shakeY) {
                // ZEMİN
                drawRect(Brush.verticalGradient(listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))))
                
                // BULUTLAR (Atmosfer)
                engine.clouds.forEach { c ->
                    drawCircle(Color.White.copy(alpha = 0.15f), 150f, c)
                    drawCircle(Color.White.copy(alpha = 0.1f), 100f, c + Offset(80f, 30f))
                }

                // OBA (Alt Bölge)
                drawOba(this)

                // KULELER
                engine.towers.forEach { drawAdvancedTower(this, it) }

                // DÜŞMANLAR
                engine.enemies.forEach { drawEpicMonster(this, it) }

                // PROJELER
                engine.projectiles.forEach { 
                    drawCircle(if(it.isIce) Color.Cyan else Color(0xFFFFD600), 8f, Offset(it.x, it.y))
                    if(!it.isIce) drawLine(Color.White, Offset(it.x, it.y), Offset(it.x - cos(it.angle)*30, it.y - sin(it.angle)*30), 3f)
                }

                // PARÇACIKLAR
                engine.particles.forEach { 
                    drawCircle(it.color.copy(alpha = it.life), it.size * it.life, Offset(it.x, it.y))
                }
            }
        }

        // GELİŞMİŞ UI
        GameUI(engine)
    }
}

private fun drawOba(scope: DrawScope) {
    val h = scope.size.height
    val w = scope.size.width
    // Savunma Hattı Çitleri
    scope.drawRect(Color(0xFF3E2723), Offset(0f, h - 340f), Size(w, 40f))
    for(i in 0..20) {
        scope.drawLine(Color(0xFF5D4037), Offset(i * (w/20), h-350f), Offset(i * (w/20), h-300f), 10f)
    }
    // Oba Zemini
    scope.drawRect(Color(0xFF4E342E), Offset(0f, h-300f), Size(w, 300f))
}

private fun drawAdvancedTower(scope: DrawScope, t: Tower) {
    val color = t.type.color
    // Ana Gövde
    scope.drawRect(color, Offset(t.x - 40f, t.y - 80f), Size(80f, 100f))
    // Üst Kısım
    scope.drawRect(Color(0xFF212121), Offset(t.x - 50f, t.y - 110f), Size(100f, 30f))
    // Kule Tipi Göstergesi (Enerji Küresi)
    scope.drawCircle(if(t.type.isIce) Color.Cyan else Color.Red, 15f, Offset(t.x, t.y - 120f))
}

private fun drawEpicMonster(scope: DrawScope, e: Enemy) {
    val size = e.type.size
    val color = if(e.slowDuration > 0) Color.Cyan else e.type.color
    
    scope.rotate(if(e.type.isBoss) 0f else (System.currentTimeMillis() % 360).toFloat() * 0.1f, Offset(e.x, e.y)) {
        // Gövde (Zırhlı Görünüm)
        scope.drawCircle(color, size, Offset(e.x, e.y))
        scope.drawCircle(Color.Black.copy(0.3f), size * 0.7f, Offset(e.x, e.y))
        
        // Boynuzlar / Kalkan
        if(e.type == EnemyType.KALKANLI) {
            scope.drawRect(Color.LightGray, Offset(e.x + size * 0.5f, e.y - size), Size(15f, size * 2))
        }
        
        // Gözler (Parlayan)
        scope.drawCircle(Color.White, 8f, Offset(e.x - size*0.4f, e.y - size*0.3f))
        scope.drawCircle(Color.White, 8f, Offset(e.x + size*0.4f, e.y - size*0.3f))
        scope.drawCircle(Color.Red, 4f, Offset(e.x - size*0.4f, e.y - size*0.3f))
        scope.drawCircle(Color.Red, 4f, Offset(e.x + size*0.4f, e.y - size*0.3f))
    }

    // Boss Etiketi
    if(e.type.isBoss) {
        scope.drawCircle(Color.Yellow.copy(0.2f), size * 1.5f, Offset(e.x, e.y))
    }

    // Sağlık Barı
    val healthPct = e.currentHealth.toFloat() / e.type.health
    scope.drawRect(Color.Black, Offset(e.x - size, e.y - size - 25f), Size(size*2, 10f))
    scope.drawRect(if(healthPct > 0.5) Color.Green else Color.Red, Offset(e.x - size, e.y - size - 25f), Size(size*2 * healthPct, 10f))
}

@Composable
fun GameUI(engine: KingShotEngine) {
    Box(Modifier.fillMaxSize()) {
        // Üst Bilgi Paneli
        Column(Modifier.padding(20.dp).align(Alignment.TopStart)) {
            Text("ARPAGU: OBA SAVUNMASI", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Row(Modifier.padding(top = 10.dp)) {
                StatChip("💰 ${engine.gold}", Color(0xFFFFD600))
                Spacer(Modifier.width(10.dp))
                StatChip("🛡️ %${engine.health}", if(engine.health > 30) Color.White else Color.Red)
                Spacer(Modifier.width(10.dp))
                StatChip("🌊 Dalga ${engine.wave}", Color.Cyan)
            }
        }

        // Kule Seçme Menüsü (Sağ Alt)
        Column(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            TowerButton("Okçu (100)", TowerType.OKCU, engine)
            Spacer(Modifier.height(10.dp))
            TowerButton("Buz (200)", TowerType.BUZ, engine)
        }
    }

    if (engine.health <= 0) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.9f)), contentAlignment = Alignment.Center) {
            Text("OBA YAĞMALANDI", color = Color.Red, fontSize = 40.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun TowerButton(name: String, type: TowerType, engine: KingShotEngine) {
    val isSelected = engine.selectedTowerType == type
    Box(
        Modifier
            .size(100.dp, 50.dp)
            .background(if (isSelected) Color.White else Color.Black.copy(0.5f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(4.dp)
            .pointerInput(type) { detectTapGestures { engine.selectedTowerType = type } },
        contentAlignment = Alignment.Center
    ) {
        Text(name, color = if (isSelected) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatChip(text: String, color: Color) {
    Box(Modifier.background(Color.Black.copy(0.6f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = color, fontWeight = FontWeight.Bold)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KingShotGame() }
    }
}
